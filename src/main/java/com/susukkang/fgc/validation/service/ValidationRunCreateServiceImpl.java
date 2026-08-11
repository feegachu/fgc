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

    // findNextRunNo(사전 조회)와 insert(실제 반영) 사이는 잠기지 X
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
        // 트랜잭션 안에서는 재시도 INSERT조차 거부
        // 재시도마다 REQUIRES_NEW로 완전히 새 트랜잭션을 열어야함
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public ValidationRunRow create(CreateValidationRunCommand command) {
        if (command.runNo() != null) {
            return createWithExplicitRunNo(command);
        }

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
     * 명시적 runNo(배치가 JobParameters로 넘긴 값)로 생성할 때는 멱등적으로 동작해야 한다 —
     * INSERT는 커밋됐는데 그 뒤 Step 완료 기록 전에 배치가 중단되면, 재시작이 같은
     * (validationMonth, runNo)로 이 메서드를 다시 부른다. 매번 새 INSERT를 시도하면
     * uq_validation_run 위반으로 재시작 자체가 실패한다 — 그래서 INSERT 전에 먼저 같은
     * 업무키의 기존 행이 있는지 확인하고, 있으면(그리고 같은 요청이면) 그 행을 그대로
     * 돌려준다. 업무키는 같은데 runType/triggeredBy가 다르면 서로 다른 요청이 같은 회차를
     * 다투는 것이므로 충돌로 처리한다.
     */
    private ValidationRunRow createWithExplicitRunNo(CreateValidationRunCommand command) {
        ValidationRunRow existing = validationRunMapper.findByMonthAndRunNo(command.validationMonth(), command.runNo());
        if (existing != null) {
            if (matchesRequest(existing, command)) {
                return existing;
            }
            throw new FgcBusinessException(FgcErrorCode.VRUN_005, Map.of(
                    "validationMonth", command.validationMonth(), "runNo", command.runNo()));
        }
        return requiresNewTransactionTemplate.execute(status -> attemptCreate(command));
    }

    private boolean matchesRequest(ValidationRunRow existing, CreateValidationRunCommand command) {
        return command.runType().name().equals(existing.getRunType())
                && command.triggeredBy().equals(existing.getTriggeredBy());
    }

    /**
     * 위반된 제약이 uq_validation_run(run_no 채번 충돌)인지 판정
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
        // 1. MONTHLY 중복 체크
        if (command.runType() == ValidationRunType.MONTHLY
                && validationRunMapper.existsActiveMonthlyRun(command.validationMonth())) {
            throw new FgcBusinessException(FgcErrorCode.VRUN_001, Map.of());
        }

        // 2. run_no 채번
        int runNo = command.runNo() != null
                ? command.runNo()
                : validationRunMapper.findNextRunNo(command.validationMonth());

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
