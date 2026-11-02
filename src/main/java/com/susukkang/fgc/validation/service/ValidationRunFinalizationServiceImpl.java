package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.audit.service.AuditLogService;
import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.validation.dto.FinalizeChecklistConditionResponse;
import com.susukkang.fgc.validation.dto.FinalizeChecklistCounts;
import com.susukkang.fgc.validation.dto.FinalizeChecklistResponse;
import com.susukkang.fgc.validation.dto.FinalizeValidationRunResponse;
import com.susukkang.fgc.validation.dto.FinalizedValidationRunRow;
import com.susukkang.fgc.validation.entity.ValidationRun;
import com.susukkang.fgc.validation.event.ValidationRunFinalized;
import com.susukkang.fgc.validation.repository.ValidationRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** FGC-FUN-044 검증 실행 확정 체크리스트와 원자적 확정 처리. */
@Service
@RequiredArgsConstructor
public class ValidationRunFinalizationServiceImpl implements ValidationRunFinalizationService {

    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 160;

    private final ValidationRunRepository validationRunRepository;
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
            Optional<Long> keyOwner = validationRunRepository.findValidationRunIdByFinalizeIdempotencyKey(normalizedKey);
            if (keyOwner.isPresent() && !keyOwner.get().equals(validationRunId)) {
                throw new FgcBusinessException(FgcErrorCode.VRUN_006,
                        Map.of("id", validationRunId, "idempotencyKey", normalizedKey));
            }
        }

        // 체크리스트 재조회부터 FINALIZED 전이까지 같은 부모 행을 잠가 확정 사이에
        // 다른 요청이 상태나 결과 스냅샷을 바꾸지 못하도록 직렬화한다.
        ValidationRun lockedRun = validationRunRepository.findByIdForUpdate(validationRunId)
                .orElseThrow(() -> notFound(validationRunId));

        if (lockedRun.getStatus() == ValidationRunStatus.FINALIZED) {
            FinalizedValidationRunRow finalized = requireFinalization(validationRunId);
            if (normalizedKey != null && normalizedKey.equals(finalized.getFinalizeIdempotencyKey())) {
                return response(finalized);
            }
            throw new FgcBusinessException(FgcErrorCode.VRUN_003, Map.of("id", validationRunId));
        }

        // 2026-10-12 yslee - #494 확정할 수 있는 실행 유형 제한 (운영정책서 제44조)
        // 기존 코드: 상태(COMPLETED/8)만 보고 실행 유형은 보지 않음
        // 문제: 변경된 계약만 계산하는 MANUAL_CONTRACT 실행도 COMPLETED/8 이 되어, 일부 계약의 결과가
        //       그 달의 확정 결과로 잠길 수 있었음
        // 개선: 월 전체를 계산한 유형만 확정한다. 이미 FINALIZED 인 실행의 멱등 재요청(위)은 그대로 응답한다.
        ValidationRunType runType = ValidationRunType.valueOf(lockedRun.getRunType());
        if (!runType.isFinalizable()) {
            throw new FgcBusinessException(FgcErrorCode.VRUN_004,
                    Map.of("from", runType.label() + " 실행", "to", "FINALIZED"));
        }

        if (lockedRun.getStatus() != ValidationRunStatus.COMPLETED || lockedRun.getCurrentStep() != 8) {
            throw new FgcBusinessException(FgcErrorCode.VRUN_004,
                    Map.of("from", lockedRun.getStatus(), "to", "FINALIZED"));
        }

        // 2026-10-11 yslee - #491 PR 리뷰 반영: 조건 7의 check-then-act 경합 차단
        // 기존 코드: 실행 행 잠금(findByIdForUpdate)만 잡은 채 "더 최근 실행 없음"을 확인
        // 문제: 확인 직후 같은 달 새 실행이 INSERT·커밋되면 이전 실행이 그대로 FINALIZED 로 잠김
        // 개선: 체크리스트 계산 전에 검증월 잠금을 잡는다. 실행 생성도 같은 잠금을 잡으므로
        //       생성 중이면 그 커밋을 기다린 뒤 새 스냅샷(READ COMMITTED, 문장 단위)으로 다시 센다.
        validationRunRepository.lockValidationMonth(lockedRun.getValidationMonth());

        // 조회 API 결과를 신뢰하지 않고 확정 트랜잭션에서 8개 조건을 다시 계산한다.
        // 조건 7(더 최근 실행)·8(이미 확정된 실행)은 위 검증월 잠금을 잡은 뒤 세야 경합이 없다.
        FinalizeChecklistResponse checklist = buildChecklist(requireChecklistCounts(validationRunId));
        long remaining = checklist.remainingConditionCount();
        if (remaining > 0) {
            throw new FgcBusinessException(FgcErrorCode.VRUN_002, Map.of("n", remaining));
        }

        int affected = validationRunRepository.finalizeIfCompleted(
                validationRunId, finalizedBy, normalizedKey,
                ValidationRunStatus.COMPLETED, ValidationRunStatus.FINALIZED);
        if (affected != 1) {
            FinalizedValidationRunRow concurrent =
                    validationRunRepository.findFinalizationById(validationRunId).orElse(null);
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
        return validationRunRepository.findFinalizeChecklistCounts(validationRunId)
                .orElseThrow(() -> notFound(validationRunId));
    }

    private FinalizedValidationRunRow requireFinalization(Long validationRunId) {
        return validationRunRepository.findFinalizationById(validationRunId)
                .orElseThrow(() -> notFound(validationRunId));
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
                // 조건 3·4는 집계 쿼리와 같은 월(validation_month) 기준으로 조회해야
                // 미충족 건수와 바로가기 목록이 일치한다(#330). 파라미터는 ISO 날짜(월 1일)다.
                condition(3, "심각도 긴급(CRITICAL) 미처리 예외가 0건인가",
                        counts.getUnresolvedCriticalExceptionCount(),
                        "/exceptions?validationMonth=" + counts.getValidationMonth()
                                + "&severity=CRITICAL&status=OPEN"),
                condition(4, "정책 없음 · 정책 중복이 0건인가",
                        counts.getUnresolvedPolicyExceptionCount(),
                        "/exceptions?validationMonth=" + counts.getValidationMonth()
                                + "&types=POLICY_MISSING&types=POLICY_DUPLICATE&status=OPEN"),
                condition(5, "귀속합계 오류가 0건인가",
                        counts.getAttributionImbalanceCount(),
                        "/transactions?settlementMonth=" + month + "&attributionImbalanceOnly=true"),
                condition(6, "계약별 상세 합계 = 실행 요약 합계인가",
                        counts.getCapDetailMismatchCount(),
                        "/validation-runs/" + id),
                // #491: 같은 달을 다시 검증했다면 이전 실행의 결과를 잠그지 않는다.
                // 바로가기는 가장 최근 실행으로 보낸다 — 사용자가 확정해야 할 대상이 그쪽이다.
                // 확정 경로에서는 finalizeRun 이 검증월 잠금을 잡은 뒤 이 값을 세므로 경합이 없다.
                // getChecklist(조회 전용)는 잠그지 않는다 — 화면 표시용이고 확정 때 다시 센다.
                condition(7, "같은 검증월의 가장 최근 실행인가",
                        counts.getSupersededRunCount(),
                        "/validation-runs/" + (counts.getLatestRunId() != null ? counts.getLatestRunId() : id)),
                // #494: 확정은 그 달을 대표하는 한 건이다. 확정된 달에도 재검증 실행은 만들 수 있지만
                // (FUN-044 "새 실행번호로 생성되어 이전 결과와 비교") 그 실행은 확정하지 않는다.
                // 바로가기는 이미 확정된 실행 — 비교 기준이 그쪽이다.
                condition(8, "같은 검증월에 이미 확정된 실행이 없는가",
                        counts.getFinalizedRunCount(),
                        "/validation-runs/" + (counts.getFinalizedRunId() != null ? counts.getFinalizedRunId() : id))
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
