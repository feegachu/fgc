package com.susukkang.fgc.reconciliation.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.RequestIdContext;
import com.susukkang.fgc.reconciliation.dto.CreateReconciliationRunCommand;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunInsertRow;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunRow;
import com.susukkang.fgc.reconciliation.mapper.ReconciliationRunMapper;
import com.susukkang.fgc.reconciliation.port.ReconciliationExecutionRequest;
import com.susukkang.fgc.reconciliation.port.ReconciliationExecutionRequestPort;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.mapper.ValidationRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 설명 : 참조 검증과 생명주기 전이를 포함한 대사 실행 생성 서비스
 *
 * @author yslee
 * @since 2026-08-12
 * @version 1.2
 */
@Service
@RequiredArgsConstructor
public class ReconciliationRunServiceImpl implements ReconciliationRunService {

    private final ReconciliationRunMapper reconciliationRunMapper;
    private final ValidationRunMapper validationRunMapper;
    private final ReconciliationRunLifecycleService lifecycleService;
    private final Optional<ReconciliationExecutionRequestPort> executionRequestPort;

    @Override
    @Transactional
    public ReconciliationRunRow create(CreateReconciliationRunCommand command) {
        validateReferences(command);
        rejectDuplicateRun(command);

        ReconciliationExecutionRequestPort executionPort = executionRequestPort.orElseThrow(() ->
                new FgcBusinessException(
                        FgcErrorCode.COMMON_500,
                        null,
                        Map.of("requestId", Objects.toString(RequestIdContext.current(), "")),
                        "대사 실행기가 아직 연결되지 않았습니다."
                ));

        ReconciliationRunInsertRow insertRow = ReconciliationRunInsertRow.from(command);
        reconciliationRunMapper.insert(insertRow);

        // 2026-08-12 yslee - 실제 실행 요청이 등록된 실행만 RUNNING으로 커밋
        // 기존 코드: 실행기 연결 없이 reconciliation_run 상태만 즉시 RUNNING으로 변경
        // 문제: 실제 매칭이 시작되지 않아 RUNNING 상태가 영구 고착될 수 있음
        // 개선: 기존 상태전이 서비스를 거친 뒤 커밋 후 실행 포트에 전달할 요청을 등록하고 실패 시 전체 롤백
        lifecycleService.start(insertRow.getReconciliationRunId());
        executionPort.requestExecution(new ReconciliationExecutionRequest(
                insertRow.getReconciliationRunId(),
                command.validationRunId(),
                command.settlementMonth(),
                command.paymentStage(),
                command.insurerId(),
                command.createdBy()
        ));

        ReconciliationRunRow created = reconciliationRunMapper.findById(insertRow.getReconciliationRunId());
        if (created == null) {
            throw new IllegalStateException("생성된 대사 실행을 조회하지 못했습니다.");
        }
        return created;
    }

    private void rejectDuplicateRun(CreateReconciliationRunCommand command) {
        ReconciliationRunRow existing = reconciliationRunMapper.findByNaturalKey(
                command.settlementMonth(),
                command.paymentStage(),
                command.insurerId(),
                command.validationRunId()
        );
        if (existing != null) {
            throw new FgcBusinessException(
                    FgcErrorCode.RECO_002,
                    Map.of(
                            "reconciliationRunId", existing.getReconciliationRunId(),
                            "status", existing.getStatus()
                    )
            );
        }
    }

    private void validateReferences(CreateReconciliationRunCommand command) {
        if (!reconciliationRunMapper.existsActiveInsurer(command.insurerId())) {
            notFound("insurerId", command.insurerId());
        }

        ValidationRunRow validationRun = validationRunMapper.findById(command.validationRunId());
        if (validationRun == null) {
            notFound("validationRunId", command.validationRunId());
        }

        if (!command.settlementMonth().equals(validationRun.getValidationMonth())) {
            throw new FgcBusinessException(
                    FgcErrorCode.COMMON_002,
                    "validationRunId",
                    Map.of("field", "validationRunId"),
                    "검증 실행의 기준월과 대사 기준월이 다릅니다."
            );
        }
    }

    private static void notFound(String field, Long id) {
        throw new FgcBusinessException(
                FgcErrorCode.COMMON_004,
                field,
                Map.of("id", id),
                null
        );
    }
}
