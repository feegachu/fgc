package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.audit.service.AuditLogService;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.validation.dto.FinalizeChecklistConditionResponse;
import com.susukkang.fgc.validation.dto.FinalizeChecklistCounts;
import com.susukkang.fgc.validation.dto.FinalizeChecklistResponse;
import com.susukkang.fgc.validation.dto.FinalizeValidationRunResponse;
import com.susukkang.fgc.validation.dto.FinalizedValidationRunRow;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.event.ValidationRunFinalized;
import com.susukkang.fgc.validation.mapper.ValidationRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
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
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;

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

        // 2026-08-19 yslee - 다른 검증 실행에 귀속된 확정 멱등키 사전 검사 오류코드 정합성 수정
        // 기존 코드: 멱등키 소유자가 다른 실행이면 상태 경합 코드 FGC-VRUN-005를 반환
        // 문제: IF-API-51 명세는 앱 사전검사와 DB UNIQUE 제약 모두 FGC-VRUN-006을 반환하도록 규정함
        // 개선: 사전 조회 충돌도 FGC-VRUN-006으로 통일해 클라이언트가 새 키로 재시도하게 함
        if (normalizedKey != null) {
            Long keyOwner = validationRunMapper.findValidationRunIdByFinalizeIdempotencyKey(normalizedKey);
            if (keyOwner != null && !keyOwner.equals(validationRunId)) {
                throw new FgcBusinessException(FgcErrorCode.VRUN_006,
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
        // 2026-08-19 yslee - 검증 실행 확정 이벤트 발행 복원
        // 기존 코드: 확정 상태와 감사로그만 저장하고 화면 잠금 연동 이벤트를 발행하지 않음
        // 문제: IF-EVT-07 구독자가 확정 완료를 인지할 수 없음
        // 개선: 최초 확정 성공 후에만 ValidationRunFinalized 이벤트를 한 번 발행
        eventPublisher.publishEvent(new ValidationRunFinalized(
                validationRunId, lockedRun.getValidationMonth(), finalizedBy, finalized.getFinalizedAt()));
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
                        "/exceptions?validationRunId=" + id + "&severity=CRITICAL&status=OPEN"),
                condition(4, "정책 없음 · 정책 중복이 0건인가",
                        counts.getUnresolvedPolicyExceptionCount(),
                        "/exceptions?validationRunId=" + id
                                + "&types=POLICY_MISSING&types=POLICY_DUPLICATE&status=OPEN"),
                condition(5, "귀속합계 오류가 0건인가",
                        counts.getAttributionImbalanceCount(),
                        "/transactions?settlementMonth=" + month + "&attributionImbalanceOnly=true"),
                condition(6, "계약별 상세 합계 = 실행 요약 합계인가",
                        counts.getCapDetailMismatchCount(),
                        "/validation-runs/" + id)
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
        // 2026-08-19 yslee - FGC-FUN-044 확정 감사로그를 FUN-061 공통 기록 경로로 통합
        // 기존 코드: AuditLogMapper를 직접 호출하며 clientIp를 항상 null로 저장
        // 문제: HTTP 확정 요청의 접속 IP가 누락되어 동일한 핵심 업무 감사로그와 형식이 달라짐
        // 개선: AuditLogService가 요청 ID·클라이언트 IP·JSON 직렬화를 공통 규칙으로 처리
        auditLogService.record(AuditLogService.AuditEvent.builder()
                .userId(finalizedBy)
                .actionCode("VALIDATION_RUN_FINALIZED")
                .entityType("VALIDATION_RUN")
                .entityId(String.valueOf(finalized.getValidationRunId()))
                .before(Map.of("status", "COMPLETED", "currentStep", 8))
                .after(Map.of(
                        "status", "FINALIZED",
                        "currentStep", 10,
                        "finalizedBy", finalizedBy,
                        "finalizedAt", finalized.getFinalizedAt()
                ))
                .reason("월 통합검증 결과 확정(검증 결과 잠금; 실제 송금·법정 회계마감 아님)")
                .build());
    }

    private FinalizeValidationRunResponse response(FinalizedValidationRunRow row) {
        return new FinalizeValidationRunResponse(row.getStatus(), row.getFinalizedAt(), row.getFinalizedBy());
    }

    private FgcBusinessException notFound(Long validationRunId) {
        return new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("id", validationRunId));
    }

}
