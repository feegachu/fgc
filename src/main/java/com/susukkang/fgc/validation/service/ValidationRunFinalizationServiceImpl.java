package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.audit.dto.AuditLogInsertRow;
import com.susukkang.fgc.audit.mapper.AuditLogMapper;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.web.RequestIdContext;
import com.susukkang.fgc.validation.dto.FinalizeChecklistConditionResponse;
import com.susukkang.fgc.validation.dto.FinalizeChecklistCounts;
import com.susukkang.fgc.validation.dto.FinalizeChecklistResponse;
import com.susukkang.fgc.validation.dto.FinalizeValidationRunResponse;
import com.susukkang.fgc.validation.dto.FinalizedValidationRunRow;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.mapper.ValidationRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/** FGC-FUN-044 검증 실행 확정 체크리스트와 원자적 확정 처리. */
@Service
@RequiredArgsConstructor
public class ValidationRunFinalizationServiceImpl implements ValidationRunFinalizationService {

    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 160;

    private final ValidationRunMapper validationRunMapper;
    private final AuditLogMapper auditLogMapper;

    @Override
    @Transactional(readOnly = true)
    public FinalizeChecklistResponse getChecklist(Long validationRunId) {
        return buildChecklist(requireChecklistCounts(validationRunId));
    }

    @Override
    @Transactional
    @PreAuthorize(Roles.CAN_FINALIZE_VALIDATION)
    public FinalizeValidationRunResponse finalizeRun(
            Long validationRunId,
            Long finalizedBy,
            String idempotencyKey
    ) {
        if (finalizedBy == null) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_002, "finalizedBy",
                    Map.of("field", "finalizedBy"), "확정 사용자 식별자가 필요합니다.");
        }
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);

        // 선택 멱등키는 실행 간 재사용을 금지한다. UNIQUE 인덱스가 최후 방어선이며,
        // 이 사전 조회는 제약 위반보다 명확한 VRUN_005 응답을 주기 위한 것이다.
        if (normalizedKey != null) {
            Long keyOwner = validationRunMapper.findValidationRunIdByFinalizeIdempotencyKey(normalizedKey);
            if (keyOwner != null && !keyOwner.equals(validationRunId)) {
                throw new FgcBusinessException(FgcErrorCode.VRUN_005,
                        Map.of("id", validationRunId, "idempotencyKey", normalizedKey));
            }
        }

        // 체크리스트 재조회부터 FINALIZED 전이까지 같은 부모 행을 잠가 확정 사이에
        // 다른 요청이 상태나 결과 스냅샷을 바꾸지 못하도록 직렬화한다.
        ValidationRunRow lockedRun = validationRunMapper.findByIdForUpdate(validationRunId);
        if (lockedRun == null) {
            throw notFound(validationRunId);
        }

        if ("FINALIZED".equals(lockedRun.getStatus())) {
            FinalizedValidationRunRow finalized = requireFinalization(validationRunId);
            if (normalizedKey != null && normalizedKey.equals(finalized.getFinalizeIdempotencyKey())) {
                return response(finalized);
            }
            throw new FgcBusinessException(FgcErrorCode.VRUN_003, Map.of("id", validationRunId));
        }

        if (!"COMPLETED".equals(lockedRun.getStatus()) || lockedRun.getCurrentStep() != 8) {
            throw new FgcBusinessException(FgcErrorCode.VRUN_004,
                    Map.of("from", lockedRun.getStatus(), "to", "FINALIZED"));
        }

        // 조회 API 결과를 신뢰하지 않고 확정 트랜잭션에서 6개 조건을 다시 계산한다.
        FinalizeChecklistResponse checklist = buildChecklist(requireChecklistCounts(validationRunId));
        long remaining = checklist.remainingConditionCount();
        if (remaining > 0) {
            throw new FgcBusinessException(FgcErrorCode.VRUN_002, Map.of("n", remaining));
        }

        if (validationRunMapper.finalizeIfCompleted(validationRunId, finalizedBy, normalizedKey) != 1) {
            FinalizedValidationRunRow concurrent = validationRunMapper.findFinalizationById(validationRunId);
            if (concurrent != null
                    && "FINALIZED".equals(concurrent.getStatus())
                    && normalizedKey != null
                    && normalizedKey.equals(concurrent.getFinalizeIdempotencyKey())) {
                return response(concurrent);
            }
            throw new FgcBusinessException(FgcErrorCode.VRUN_005, Map.of("id", validationRunId));
        }

        FinalizedValidationRunRow finalized = requireFinalization(validationRunId);
        recordFinalizationAudit(finalized, finalizedBy);
        return response(finalized);
    }

    private FinalizeChecklistCounts requireChecklistCounts(Long validationRunId) {
        FinalizeChecklistCounts counts = validationRunMapper.findFinalizeChecklistCounts(validationRunId);
        if (counts == null) {
            throw notFound(validationRunId);
        }
        return counts;
    }

    private FinalizedValidationRunRow requireFinalization(Long validationRunId) {
        FinalizedValidationRunRow row = validationRunMapper.findFinalizationById(validationRunId);
        if (row == null) {
            throw notFound(validationRunId);
        }
        return row;
    }

    private FinalizeChecklistResponse buildChecklist(FinalizeChecklistCounts counts) {
        Long id = counts.getValidationRunId();
        String month = YearMonth.from(counts.getValidationMonth()).toString();
        List<FinalizeChecklistConditionResponse> conditions = List.of(
                condition(1, "검증 실행 상태가 계산완료(COMPLETED)인가",
                        counts.getIncompleteRunCount(), "/validation-runs/" + id),
                condition(2, "원장 불균형(차변≠대변)이 0건인가",
                        counts.getJournalImbalanceCount(),
                        "/api/v1/journals/imbalances?validationRunId=" + id),
                condition(3, "심각도 긴급(CRITICAL) 미처리 예외가 0건인가",
                        counts.getUnresolvedCriticalExceptionCount(),
                        "/api/v1/exceptions?validationRunId=" + id + "&severity=CRITICAL&status=OPEN"),
                condition(4, "정책 없음 · 정책 중복이 0건인가",
                        counts.getUnresolvedPolicyExceptionCount(),
                        "/api/v1/exceptions?validationRunId=" + id
                                + "&types=POLICY_MISSING&types=POLICY_DUPLICATE&status=OPEN"),
                condition(5, "귀속합계 오류가 0건인가",
                        counts.getAttributionImbalanceCount(),
                        "/transactions?settlementMonth=" + month + "&attributionImbalanceOnly=true"),
                condition(6, "계약별 상세 합계 = 실행 요약 합계인가",
                        counts.getCapDetailMismatchCount(),
                        "/validation-runs/" + id + "?section=cap-details")
        );
        return new FinalizeChecklistResponse(id,
                conditions.stream().allMatch(FinalizeChecklistConditionResponse::passed), conditions);
    }

    private FinalizeChecklistConditionResponse condition(int no, String label, long count, String linkUrl) {
        return new FinalizeChecklistConditionResponse(no, label, count == 0, count, linkUrl);
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            return null;
        }
        if (!StringUtils.hasText(idempotencyKey) || idempotencyKey.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_002, "Idempotency-Key",
                    Map.of("field", "Idempotency-Key"),
                    "멱등키는 공백이 아닌 160자 이하 문자열이어야 합니다.");
        }
        return idempotencyKey.trim();
    }

    private void recordFinalizationAudit(FinalizedValidationRunRow finalized, Long finalizedBy) {
        String afterValue = "{\"status\":\"FINALIZED\",\"currentStep\":10,"
                + "\"finalizedBy\":" + finalizedBy + ",\"finalizedAt\":\""
                + finalized.getFinalizedAt() + "\"}";
        int affected = auditLogMapper.insert(AuditLogInsertRow.builder()
                .userId(finalizedBy)
                .actionCode("VALIDATION_RUN_FINALIZED")
                .entityType("VALIDATION_RUN")
                .entityId(String.valueOf(finalized.getValidationRunId()))
                .beforeValue("{\"status\":\"COMPLETED\",\"currentStep\":8}")
                .afterValue(afterValue)
                .reason("월 통합검증 결과 확정(검증 결과 잠금; 실제 송금·법정 회계마감 아님)")
                .requestId(limit(RequestIdContext.current(), 80))
                .clientIp(null)
                .build());
        if (affected != 1) {
            throw new IllegalStateException("Validation run finalization audit insert failed");
        }
    }

    private FinalizeValidationRunResponse response(FinalizedValidationRunRow row) {
        return new FinalizeValidationRunResponse(row.getStatus(), row.getFinalizedAt(), row.getFinalizedBy());
    }

    private FgcBusinessException notFound(Long validationRunId) {
        return new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("id", validationRunId));
    }

    private String limit(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
