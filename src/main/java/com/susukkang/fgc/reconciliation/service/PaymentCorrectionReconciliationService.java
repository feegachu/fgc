package com.susukkang.fgc.reconciliation.service;

import com.susukkang.fgc.common.code.ExceptionActionType;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.exceptioncase.dto.ExceptionActionRequest;
import com.susukkang.fgc.exceptioncase.service.ExceptionCaseService;
import com.susukkang.fgc.journal.dto.JournalCorrectionHeaderRow;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunInsertRow;
import com.susukkang.fgc.reconciliation.port.ReconciliationExecutionRequest;
import com.susukkang.fgc.reconciliation.repository.ReconciliationRunRepository;
import com.susukkang.fgc.reconciliation.repository.PaymentCorrectionReconciliationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** 과거 결과를 보존하며 현재 대사를 새 실행으로 교체한다. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class PaymentCorrectionReconciliationService {
    private final PaymentCorrectionReconciliationRepository repository;
    private final ReconciliationRunRepository runs;
    private final ReconciliationExecutionService execution;
    private final ReconciliationExceptionService detection;
    private final ExceptionCaseService exceptions;

    public List<Long> lockAffectedRuns(JournalCorrectionHeaderRow original) {
        // 월 검증 확정과 동일한 부모 잠금을 사용하고 잠금 뒤 상태를 다시 판정한다.
        Long paymentId;
        try {
            paymentId = Long.valueOf(original.getSourceEntityId());
        } catch (NumberFormatException ex) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_002, Map.of("field", "sourceEntityId"));
        }
        List<?> validations = repository.lockValidations(original.getValidationRunId(), paymentId);
        for (Object row : validations) {
            Object[] v = (Object[]) row;
            boolean ownsOriginal = original.getValidationRunId() != null
                    && ((Number) v[0]).longValue() == original.getValidationRunId();
            if ("FINALIZED".equals(v[1]) && (ownsOriginal || Boolean.TRUE.equals(v[2]))) {
                throw new FgcBusinessException(FgcErrorCode.VRUN_003, Map.of("validationRunId", v[0]));
            }
        }
        List<?> rows = repository.lockRuns(paymentId, original.getContractId());
        return rows.stream().map(row -> {
            Object[] r = (Object[]) row;
            if (!"COMPLETED".equals(r[1])) throw new FgcBusinessException(FgcErrorCode.RECO_004, Map.of());
            return ((Number) r[0]).longValue();
        }).toList();
    }

    public void refresh(List<Long> runIds, String groupKey, Long actor) {
        for (Long oldId : runIds) {
            var old = runs.findById(oldId);
            if (repository.supersede(oldId) != 1) {
                throw new FgcBusinessException(FgcErrorCode.RECO_004, Map.of());
            }
            var row = new ReconciliationRunInsertRow();
            row.setSettlementMonth(old.getSettlementMonth());
            row.setPaymentStage(PaymentStage.valueOf(old.getPaymentStage()));
            row.setInsurerId(old.getInsurerId());
            row.setValidationRunId(old.getValidationRunId());
            row.setCreatedBy(actor);
            runs.insert(row);
            Long newId = row.getReconciliationRunId();
            repository.linkRevision(oldId, newId);
            runs.transitionToRunning(newId);
            execution.executeInTransaction(new ReconciliationExecutionRequest(newId, old.getValidationRunId(),
                    old.getSettlementMonth(), row.getPaymentStage(), old.getInsurerId(), actor));
            if (old.getValidationRunId() != null) detection.bulkCreate(newId);
            resolveMatchedExceptions(oldId, newId, groupKey, actor);
        }
    }

    private void resolveMatchedExceptions(Long oldId, Long newId, String groupKey, Long actor) {
        // 동일한 업무 매칭키가 새 실행에서 MATCHED가 된 예외만 잠금 후 종결한다.
        // 다른 불일치나 원장 정정 요청 예외는 일괄 종결하지 않는다.
        List<?> ids = repository.lockMatchedExceptions(oldId, newId);
        String login = repository.loginId(actor);
        String reason = "원장 정정 후 재대사 일치 확인 (" + groupKey + ", 대사 " + newId + ")";
        for (Object value : ids) {
            Object[] row = (Object[]) value;
            Long id = ((Number) row[0]).longValue();
            if ("NEW".equals(row[1])) exceptions.action(id,
                    new ExceptionActionRequest(ExceptionActionType.START_REVIEW, reason, groupKey), actor, login);
            exceptions.action(id, new ExceptionActionRequest(ExceptionActionType.RESOLVE, reason, groupKey), actor, login);
        }
    }
}
