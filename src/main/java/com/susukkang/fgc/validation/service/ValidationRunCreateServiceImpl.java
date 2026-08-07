package com.susukkang.fgc.validation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.cap.dto.CapRuleSetView;
import com.susukkang.fgc.cap.dto.RefundRateTableView;
import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.RequestIdContext;
import com.susukkang.fgc.validation.dto.CreateValidationRunCommand;
import com.susukkang.fgc.validation.dto.ProductOfferingSnapshotView;
import com.susukkang.fgc.validation.dto.ValidationRunInsertRow;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.mapper.PolicySnapshotMapper;
import com.susukkang.fgc.validation.mapper.ValidationRunMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class ValidationRunCreateServiceImpl implements ValidationRunCreateService {

    private static final String CONSTRAINT_ACTIVE_MONTHLY_RUN = "uq_validation_run_active_month";
    private static final String CONSTRAINT_RUN_NO = "uq_validation_run";

    // findNextRunNo(사전 조회)와 insert(실제 반영) 사이는 잠기지 않는다 — 두 요청이 동시에
    // 같은 run_no를 계산해서 갈 수 있다. 그중 하나는 uq_validation_run(validation_month, run_no)
    // 위반으로 INSERT가 실패하는데, 이건 "실행이 이미 진행 중"이라는 업무 규칙 위반이 아니라
    // 단순 채번 충돌이라 몇 번 재시도하면 대부분 바로 해결된다. 무한 재시도는 위험하니 상한을 둔다.
    private static final int MAX_RUN_NO_RETRIES = 3;

    private final ValidationRunMapper validationRunMapper;
    private final PolicySnapshotMapper policySnapshotMapper;
    private final ObjectMapper objectMapper;
    private final ConstraintErrorCodeResolver constraintErrorCodeResolver;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public ValidationRunCreateServiceImpl(ValidationRunMapper validationRunMapper,
                                           PolicySnapshotMapper policySnapshotMapper,
                                           ObjectMapper objectMapper,
                                           ConstraintErrorCodeResolver constraintErrorCodeResolver,
                                           PlatformTransactionManager transactionManager) {
        this.validationRunMapper = validationRunMapper;
        this.policySnapshotMapper = policySnapshotMapper;
        this.objectMapper = objectMapper;
        this.constraintErrorCodeResolver = constraintErrorCodeResolver;
        // PostgreSQL은 제약 위반이 한 번 나면 그 트랜잭션 전체가 "aborted" 상태가 되어 같은
        // 트랜잭션 안에서는 재시도 INSERT조차 거부한다(추가 오류만 쌓인다). 그래서 재시도마다
        // REQUIRES_NEW로 완전히 새 트랜잭션을 열어야 한다 — @Transactional을 그대로 쓰면 이
        // 메서드 전체가 하나의 트랜잭션이라 자기 자신을 다시 호출해도(self-invocation) AOP
        // 프록시를 안 거쳐 새 트랜잭션이 안 열리므로, TransactionTemplate으로 명시적으로 연다.
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public ValidationRunRow create(CreateValidationRunCommand command) {
        for (int attempt = 1; attempt <= MAX_RUN_NO_RETRIES; attempt++) {
            try {
                return requiresNewTransactionTemplate.execute(status -> attemptCreate(command));
            } catch (DataIntegrityViolationException e) {
                // run_no 채번 충돌(uq_validation_run)만 재시도한다. 활성 MONTHLY 중복
                // (uq_validation_run_active_month)이나 그 밖의 무결성 위반은 재시도해도
                // 해결되지 않으므로 그대로 던져 기존 예외 처리 경로(GlobalExceptionHandler →
                // ConstraintErrorCodeResolver)를 타게 둔다.
                if (!isRunNoCollision(e) || attempt == MAX_RUN_NO_RETRIES) {
                    throw e;
                }
                log.warn("validation_run run_no 충돌로 재시도합니다 (시도 {}/{})", attempt, MAX_RUN_NO_RETRIES);
            }
        }
        throw new IllegalStateException("unreachable: MAX_RUN_NO_RETRIES 루프를 정상적으로 빠져나올 수 없다");
    }

    /**
     * 위반된 제약이 uq_validation_run(run_no 채번 충돌)인지 판정한다.
     * ConstraintErrorCodeResolver#extractConstraintName으로 PostgreSQL이 알려주는 정확한
     * 제약 이름을 먼저 쓴다 — uq_validation_run과 uq_validation_run_active_month가 서로
     * 접두어 관계라 메시지 문자열 부분일치만으로는 안전하게 구분할 수 없기 때문이다.
     * 실제 PSQLException이 없는 경우(단위테스트의 합성 예외 등)에만 메시지 기반 대체 판정으로
     * 넘어간다.
     */
    private boolean isRunNoCollision(DataIntegrityViolationException e) {
        Optional<String> constraintName = constraintErrorCodeResolver.extractConstraintName(e);
        if (constraintName.isPresent()) {
            return CONSTRAINT_RUN_NO.equals(constraintName.get());
        }

        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage() == null ? "" : root.getMessage().toLowerCase(Locale.ROOT);
        return message.contains(CONSTRAINT_RUN_NO) && !message.contains(CONSTRAINT_ACTIVE_MONTHLY_RUN);
    }

    private ValidationRunRow attemptCreate(CreateValidationRunCommand command) {
        // 1. MONTHLY 중복 체크 — 앱 레벨 사전 체크(친절한 메시지용). 진짜 방어선은 DB의
        // uq_validation_run_active_month이고, 이 사전 체크를 두 요청이 동시에 통과해도
        // INSERT 단계에서 결국 하나만 성공한다(위 isActiveMonthlyRunConflict 참고).
        if (command.runType() == ValidationRunType.MONTHLY
                && validationRunMapper.existsActiveMonthlyRun(command.validationMonth())) {
            throw new FgcBusinessException(FgcErrorCode.VRUN_001, Map.of());
        }

        // 2. run_no 채번 — 재시도마다(=매 attempt마다) 새 트랜잭션에서 다시 계산해야 한다.
        // 그래야 방금 실패를 유발한 경쟁자의 INSERT가 이 시점에 보이는 값 기준으로 다음
        // run_no를 새로 받는다.
        int runNo = validationRunMapper.findNextRunNo(command.validationMonth());

        // 3. policy_snapshot
        List<CapRuleSetView> capRuleSets = policySnapshotMapper.findActiveCapRuleSets(command.validationMonth());
        List<RefundRateTableView> refundRateTables = policySnapshotMapper.findActiveRefundRateTables(command.validationMonth());
        List<ProductOfferingSnapshotView> productOfferings = policySnapshotMapper.findActiveProductOfferings(command.validationMonth());

        Map<String, Object> policySnapshot = new HashMap<>();
        policySnapshot.put("capRuleSets", capRuleSets);
        policySnapshot.put("refundRateTables", refundRateTables);
        policySnapshot.put("productOfferings", productOfferings);

        // 4. INSERT
        ValidationRunInsertRow row = ValidationRunInsertRow.builder()
                .validationMonth(command.validationMonth())
                .runType(command.runType().name())
                .runNo(runNo)
                .triggeredBy(command.triggeredBy())
                .policySnapshotJson(writeJson(policySnapshot))
                .build();
        validationRunMapper.insert(row);

        // 5. 반환
        return validationRunMapper.findById(row.getValidationRunId());
    }

    // 정책 스냅샷 Map을 JSON 문자열로 직렬화
    // 실패하면 "빈 스냅샷으로 조용히 저장" 대신 예외로 트랜잭션을 롤백
    private String writeJson(Map<String, Object> snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            String requestId = RequestIdContext.current();
            log.error("[{}] policy_snapshot 직렬화 실패", requestId, e);
            throw new FgcBusinessException(FgcErrorCode.COMMON_500, Map.of("requestId", requestId));
        }
    }
}
