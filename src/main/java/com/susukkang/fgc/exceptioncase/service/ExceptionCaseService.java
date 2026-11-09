package com.susukkang.fgc.exceptioncase.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.code.ExceptionActionType;
import com.susukkang.fgc.common.code.ExceptionSeverity;
import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.common.code.ExceptionType;
import com.susukkang.fgc.audit.entity.AuditLog;
import com.susukkang.fgc.audit.repository.AuditLogRepository;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.common.web.ListSort;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.common.web.RequestIdContext;
import com.susukkang.fgc.exceptioncase.dto.*;
import com.susukkang.fgc.exceptioncase.entity.ExceptionAction;
import com.susukkang.fgc.exceptioncase.repository.ExceptionActionRepository;
import com.susukkang.fgc.exceptioncase.repository.ExceptionCaseRepository;
import com.susukkang.fgc.exceptioncase.repository.ExceptionCaseQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 설명 : IF-API-43·44 예외함 검색과 처리 서비스
 *
 * @author yslee
 * @since 2026-08-20
 * @version 1.2
 */
@Service
@RequiredArgsConstructor
public class ExceptionCaseService {

    private static final int MIN_PAGE = 1;
    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 100;
    private static final String SORT = "severity,desc";

    private final ExceptionCaseQueryRepository exceptionCaseQueryRepository;
    private final ExceptionCaseRepository exceptionCaseRepository;
    private final ExceptionActionRepository exceptionActionRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    /**
     * 조건에 맞는 예외 한 페이지와 각 예외의 처리 이력을 조회한다.
     * 처리 이력은 페이지의 ID 목록으로 한 번에 조회하여 예외 건별 N+1 쿼리를 만들지 않는다.
     */
    @Transactional(readOnly = true)
    public ExceptionCaseSearchResponse search(ExceptionCaseSearchDTO criteria, int page, int size) {
        validatePage(page, size);
        List<ExceptionStatus> statuses = parseStatuses(criteria.getStatus());
        ListSort sort = ExceptionCaseQueryRepository.sortOf(criteria);

        // 총 건수를 먼저 세어 범위 밖 page 를 마지막 페이지로 보정한다 — 큰 OFFSET 으로
        // 정렬·조인을 헛도는 행 조회를 아예 만들지 않는다. 응답의 page 가 보정된 값이다.
        long total = exceptionCaseQueryRepository.count(criteria, statuses);
        long totalPages = (total + size - 1) / size;
        if (totalPages == 0) {
            page = MIN_PAGE; // 0건이면 화면이 "N / 1 페이지"로 어긋나지 않게 1로 되돌린다
        } else if (page > totalPages) {
            page = (int) totalPages;
        }

        long offsetLong = (long) (page - 1) * size;
        if (offsetLong > Integer.MAX_VALUE) {
            throw invalidField("page");
        }
        int offset = (int) offsetLong;

        List<ExceptionCaseSearchRow> rows = total == 0
                ? List.of()
                : exceptionCaseQueryRepository.search(criteria, statuses, offset, size);

        Map<Long, List<ExceptionActionResponse>> actionsByCaseId = loadActions(rows);
        Map<Long, List<ExceptionOccurrenceResponse>> occurrencesByCaseId = loadOccurrences(rows);
        List<ExceptionCaseResponseDTO> content = rows.stream()
                .map(row -> ExceptionCaseResponseDTO.from(
                        row,
                        occurrencesByCaseId.getOrDefault(row.exceptionCaseId(), List.of()),
                        actionsByCaseId.getOrDefault(row.exceptionCaseId(), List.of())))
                .toList();

        List<ExceptionTypeSummaryResponse> summary = exceptionCaseQueryRepository.countOpenByType()
                .stream()
                .map(ExceptionTypeSummaryResponse::from)
                .toList();

        PageResponse<ExceptionCaseResponseDTO> result =
                PageResponse.of(content, page, size, total, sort == null ? SORT : sort.value());
        return new ExceptionCaseSearchResponse(
                summary, result.content(), result.page(), result.size(), result.totalElements(),
                result.totalPages(), result.sort());
    }

    /** IF-API-69. status 해석은 IF-API-43 과 같고, 건수는 IF-API-43 요약과 같은 쿼리로 센다. */
    @Transactional(readOnly = true)
    public ExceptionCountsResponse counts(String status, LocalDate validationMonth) {
        return ExceptionCountsResponse.from(exceptionCaseQueryRepository.countByTypeAndSeverity(
                ExceptionCaseSearchDTO.builder().validationMonth(validationMonth).build(), parseStatuses(status)));
    }

    private Map<Long, List<ExceptionOccurrenceResponse>> loadOccurrences(
            List<ExceptionCaseSearchRow> rows
    ) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<Long> exceptionCaseIds = rows.stream()
                .map(ExceptionCaseSearchRow::exceptionCaseId)
                .toList();
        return exceptionCaseQueryRepository.findOccurrencesByCaseIds(exceptionCaseIds)
                .stream()
                .collect(Collectors.groupingBy(
                        ExceptionOccurrenceRow::exceptionCaseId,
                        Collectors.mapping(ExceptionOccurrenceResponse::from, Collectors.toList())
                ));
    }

    @Transactional(readOnly = true)
    public List<String> reasonCodes() {
        return exceptionCaseQueryRepository.findReasonCodes();
    }

    /** 담당자 필터 선택지 — 예외를 배정받은 적 있는 사용자만. */
    @Transactional(readOnly = true)
    public List<ExceptionAssigneeRow> assignees() {
        return exceptionCaseQueryRepository.findAssignees();
    }

    /** 검증월 필터 선택지 — 예외가 검출된 검증월만, 최신순. */
    @Transactional(readOnly = true)
    public List<LocalDate> validationMonths() {
        return exceptionCaseQueryRepository.findValidationMonths();
    }

    /** IF-API-43A 필터 선택지 — 유형·심각도는 enum 전체, 나머지는 실제 데이터에서 뽑는다. */
    @Transactional(readOnly = true)
    public ExceptionOptionsResponse options() {
        return new ExceptionOptionsResponse(
                Arrays.stream(ExceptionType.values())
                        .filter(type -> type != ExceptionType.JOURNAL_CORRECTION_REQUIRED)
                        .map(type -> new ExceptionOptionsResponse.Option(type.name(), type.label()))
                        .toList(),
                reasonCodes().stream()
                        .filter(code -> !ExceptionType.JOURNAL_CORRECTION_REQUIRED.name().equals(code))
                        .map(code -> new ExceptionOptionsResponse.Option(code, ExceptionCaseResponseDTO.labelOf(code)))
                        .toList(),
                Arrays.stream(ExceptionSeverity.values())
                        .map(severity -> new ExceptionOptionsResponse.Option(severity.name(), severity.label()))
                        .toList(),
                assignees(),
                validationMonths(),
                exceptionCaseQueryRepository.findAssignableUsers());
    }

    private Map<Long, List<ExceptionActionResponse>> loadActions(
            List<ExceptionCaseSearchRow> rows
    ) {
        if (rows.isEmpty()) {
            return Map.of();
        }

        List<Long> exceptionCaseIds = rows.stream()
                .map(ExceptionCaseSearchRow::exceptionCaseId)
                .toList();
        return exceptionCaseQueryRepository.findActionsByCaseIds(exceptionCaseIds)
                .stream()
                .collect(Collectors.groupingBy(
                        ExceptionActionRow::exceptionCaseId,
                        Collectors.mapping(ExceptionActionResponse::from, Collectors.toList())
                ));
    }

    private List<ExceptionStatus> parseStatuses(String status) {
        try {
            return ExceptionStatus.dbStatuses(status == null ? "" : status);
        } catch (IllegalArgumentException e) {
            throw invalidField("status");
        }
    }

    private void validatePage(int page, int size) {
        if (page < MIN_PAGE) {
            throw invalidField("page");
        }
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw invalidField("size");
        }
    }

    private FgcBusinessException invalidField(String field) {
        return new FgcBusinessException(
                FgcErrorCode.COMMON_002, field, Map.of("field", field), null);
    }

    @Transactional
    public ExceptionActionResponse action(
            Long exceptionCaseId,
            ExceptionActionRequest request,
            Long actionUserId,
            String actionUserLoginId) {
        return actionInternal(
                exceptionCaseId, request, actionUserId, actionUserLoginId, false);
    }

    /** 화면의 배정·검토 조치. 실제 원천 수정 없이 금액 정정이나 해결 상태를 기록하지 않는다. */
    @Transactional
    public ExceptionActionResponse userAction(Long id, ExceptionActionRequest request, Long actor, String login) {
        var target = exceptionCaseRepository.findByIdForUpdate(id);
        if (target == null) throw new FgcBusinessException(FgcErrorCode.COMMON_004,
                "exceptionCaseId", Map.of("field", "exceptionCaseId", "id", id), null);
        var type = request.actionType();
        if (type == ExceptionActionType.ASSIGN) {
            if (request.assignedTo() == null || request.assignedTo().equals(actor)
                    || !exceptionCaseQueryRepository.isAssignableUser(request.assignedTo())) throw invalidField("assignedTo");
        } else if (type == ExceptionActionType.START_REVIEW || type == ExceptionActionType.REOPEN) {
            if (target.assignedTo() != null && !target.assignedTo().equals(actor)) throw notAllowed(target, type);
        } else if (type == ExceptionActionType.COMMENT || type == ExceptionActionType.REJECT) {
            requireReviewer(target, actor);
            if (type == ExceptionActionType.REJECT && exceptionCaseQueryRepository.requiresRejectionEvidence(id)
                    && (request.evidenceRef() == null || request.evidenceRef().isBlank())) throw invalidField("evidenceRef");
            if (type == ExceptionActionType.REJECT && exceptionCaseQueryRepository.requiresAdministratorReview(id)) {
                var auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth == null || auth.getAuthorities().stream().noneMatch(a -> List.of("ROLE_GA_ADMIN", "ROLE_SYSTEM_ADMIN").contains(a.getAuthority()))) {
                    throw new org.springframework.security.access.AccessDeniedException("보험사 지급기준 한도 초과의 반려는 GA관리자 또는 시스템관리자가 근거를 확인해야 합니다.");
                }
            }
        } else {
            throw notAllowed(target, type);
        }
        return actionInternal(id, request, actor, login, false);
    }

    public void requireReviewer(ExceptionCaseActionTarget target, Long actor) {
        if (target == null) throw invalidField("exceptionCaseId");
        if (target.status() != ExceptionStatus.IN_REVIEW || !actor.equals(target.assignedTo())) {
            throw notAllowed(target, ExceptionActionType.CORRECT);
        }
    }

    private FgcBusinessException notAllowed(ExceptionCaseActionTarget target, ExceptionActionType type) {
        return new FgcBusinessException(FgcErrorCode.EXCP_003,
                Map.of("status", target.status().name(), "actionType", type.name()));
    }

    /** 실제 지급은 수정됐지만 원래 검증 원인이 남은 경우에도 감액·이월·정정 이력을 보존한다. */
    @Transactional
    public ExceptionActionResponse recordPendingPaymentCorrection(Long id, ExceptionActionRequest request, Long actor, String login) {
        if (!List.of(ExceptionActionType.REDUCE, ExceptionActionType.DEFER, ExceptionActionType.CORRECT, ExceptionActionType.RECONCILE_AGAIN, ExceptionActionType.CANCEL).contains(request.actionType())) throw invalidField("actionType");
        requireReviewer(exceptionCaseRepository.findByIdForUpdate(id), actor);
        return actionInternal(id, request, actor, login, false, true);
    }

    /** 실제 원장 reverseAndRepost 성공 뒤 같은 트랜잭션에서만 호출하는 전용 종결 경로. */
    @Transactional
    public ExceptionActionResponse actionAfterJournalCorrection(
            Long exceptionCaseId,
            ExceptionActionRequest request,
            Long actionUserId,
            String actionUserLoginId) {
        if (request.actionType() != ExceptionActionType.CORRECT) {
            throw new IllegalArgumentException("원장 정정 후속 조치는 CORRECT만 허용합니다.");
        }
        return actionInternal(
                exceptionCaseId, request, actionUserId, actionUserLoginId, true);
    }

    /** 삭제할 지급에서 발생한 열린 예외만 취소 조치로 종결하고 기존 검출·조치 이력을 보존한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void resolveForDraftDeletion(Long paymentId) {
        var targets = exceptionCaseRepository.findPaymentCasesForUpdate(paymentId.toString()).stream()
                .filter(target -> !isClosed(ExceptionStatus.valueOf(target.getStatus())))
                .toList();
        if (targets.isEmpty()) return;
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        FgcUserDetails user = authentication != null
                && authentication.getPrincipal() instanceof FgcUserDetails details ? details : null;
        if (user == null) throw new org.springframework.security.access.AccessDeniedException("예외 조치 처리자 정보가 필요합니다.");
        Long userId = user.getUserId();
        String loginId = user.getUsername();
        String reason = "지급 초안 삭제로 종결, 지급 건 " + paymentId;
        for (var target : targets) {
            ExceptionStatus status = ExceptionStatus.valueOf(target.getStatus());
            if (status == ExceptionStatus.NEW) {
                actionInternal(target.getExceptionCaseId(),
                        new ExceptionActionRequest(ExceptionActionType.START_REVIEW, reason, null),
                        userId, loginId, false);
            }
            actionInternal(target.getExceptionCaseId(),
                    new ExceptionActionRequest(ExceptionActionType.CANCEL, reason, null),
                    userId, loginId, false);
        }
    }

    /**
     * 설명 : 예외 조치와 상태 변경 및 감사로그를 호출자의 같은 트랜잭션에서 저장한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-27
     */
    private ExceptionActionResponse actionInternal(
            Long exceptionCaseId,
            ExceptionActionRequest request,
            Long actionUserId,
            String actionUserLoginId,
            boolean journalCorrectionExecuted) {
        return actionInternal(exceptionCaseId, request, actionUserId, actionUserLoginId, journalCorrectionExecuted, false);
    }

    private ExceptionActionResponse actionInternal(Long exceptionCaseId, ExceptionActionRequest request,
            Long actionUserId, String actionUserLoginId, boolean journalCorrectionExecuted, boolean keepInReview) {

        if (request.reason() == null || request.reason().isBlank()) throw new FgcBusinessException(FgcErrorCode.EXCP_001);
        String reason = request.reason().trim();

        // 1. 동시에 같은 예외를 처리하지 못하도록 행 잠금
        ExceptionCaseActionTarget target =
                exceptionCaseRepository.findByIdForUpdate(exceptionCaseId);

        if (target == null) {
            throw new FgcBusinessException(
                    FgcErrorCode.COMMON_004,
                    "exceptionCaseId",
                    Map.of("field", "exceptionCaseId", "id", exceptionCaseId),
                    null
            );
        }

        ExceptionStatus fromStatus = target.status();
        ExceptionActionType actionType = request.actionType();

        if (actionType == ExceptionActionType.REOPEN && exceptionCaseRepository.isDeletedPaymentCase(exceptionCaseId)) {
            throw new FgcBusinessException(FgcErrorCode.EXCP_003,
                    Map.of("status", fromStatus.name(), "actionType", actionType.name()));
        }

        // 2026-08-20 yslee - 원장 정정 예외의 형식적 종결을 일반 조치 API에서 차단
        // 기존 코드: CORRECT·RESOLVE 이력만 저장해도 JOURNAL_CORRECTION_REQUIRED가 종결됨
        // 문제: 실제 역분개·재기표 없이 화면 상태만 해결로 바뀌어 원장과 예외가 불일치함
        // 개선: 검토 준비·오탐 조치만 일반 API로 허용하고 실제 정정은 IF-API-44A만 사용
        if (ExceptionType.JOURNAL_CORRECTION_REQUIRED.name().equals(target.exceptionType())
                && !journalCorrectionExecuted
                && !actionType.isJournalCorrectionGeneralAction()) {
            throw new FgcBusinessException(FgcErrorCode.EXCP_003,
                    Map.of("status", fromStatus.name(), "actionType", actionType.name()));
        }

        // 2. 현재 상태에서 가능한 조치인지 검증
        if (!actionType.supports(fromStatus)) {
            throw new FgcBusinessException(
                    FgcErrorCode.EXCP_003,
                    Map.of(
                            "status", fromStatus.name(),
                            "actionType", actionType.name()
                    )
            );
        }

        ExceptionStatus toStatus =
                keepInReview ? ExceptionStatus.IN_REVIEW : actionType.nextStatus(fromStatus);

        Long assignedTo = target.assignedTo();
        if (actionType == ExceptionActionType.ASSIGN) {
            // assignedTo가 없는 기존 요청은 본인 배정으로 처리한다.
            assignedTo = request.assignedTo() == null ? actionUserId : request.assignedTo();
            if (request.assignedTo() != null && !exceptionCaseQueryRepository.isAssignableUser(assignedTo)) {
                throw invalidField("assignedTo");
            }
        } else if (actionType == ExceptionActionType.START_REVIEW || actionType == ExceptionActionType.REOPEN) {
            if (request.assignedTo() != null) throw invalidField("assignedTo");
            assignedTo = actionUserId;
        } else if (request.assignedTo() != null) {
            throw invalidField("assignedTo");
        }

        // 3. 잠금을 획득한 상태에서 다음 이력 순번 계산
        int nextActionSeq =
                exceptionActionRepository.findNextActionSeq(exceptionCaseId);

        // 응답이 즉시 화면에 표시되므로 GET 조회 경로(DateUtil.toSeoul)와 같은 기준으로 맞춘다 — SIR-008
        OffsetDateTime actionAt = DateUtil.nowSeoul();

        // 2026-09-28 hjKang - 예외 조치 저장을 공용 JPA 엔티티로 전환한다.
        // 기존 코드: Mapper가 처리 이력 INSERT와 상태 UPDATE를 실행했다.
        // 문제: 공용 예외 저장 계약이 XML에 남아 있었다.
        // 개선: 부모 행 잠금과 이력 → 상태 → 감사 순서를 유지하고 하나의 트랜잭션에 참여한다.
        exceptionActionRepository.save(
                ExceptionAction.builder()
                        .exceptionCaseId(exceptionCaseId)
                        .actionSeq(nextActionSeq)
                        .fromStatus(fromStatus)
                        .toStatus(toStatus)
                        .actionType(actionType)
                        .reason(reason)
                        .evidenceRef(request.evidenceRef())
                        .actionBy(actionUserId)
                        .actionAt(actionAt)
                        .build());

        // 5. exception_case의 현재 상태 UPDATE
        OffsetDateTime resolvedAt = isClosed(toStatus) ? actionAt : null;
        int updated = exceptionCaseRepository.updateCaseAfterAction(
                exceptionCaseId,
                toStatus,
                assignedTo,
                resolvedAt);

        if (updated != 1) {
            throw new IllegalStateException(
                    "예외 상태 변경에 실패했습니다."
            );
        }

        // 2026-09-27 hjKang - 예외 조치 감사 저장을 JPA Repository로 전환한다.
        // 기존 코드: MyBatis 저장 DTO를 전달하고 INSERT 영향 행 수를 검사했다.
        // 문제: 예외 처리 감사 경로가 별도의 XML 저장 쿼리에 의존했다.
        // 개선: 변경 전후 JSON을 그대로 엔티티에 담고 저장 오류를 전파해 예외 상태·이력도 함께 롤백한다.
        AuditLog auditLog = AuditLog.create(actionUserId, "EXCEPTION_ACTION", "EXCEPTION_CASE",
                String.valueOf(exceptionCaseId),
                toJson(new ExceptionAuditValue(fromStatus, target.assignedTo(), null, null)),
                toJson(new ExceptionAuditValue(toStatus, assignedTo, actionType, request.evidenceRef())),
                reason, RequestIdContext.current(), null, null);
        auditLogRepository.saveAndFlush(auditLog);

        return new ExceptionActionResponse(
                null, // 기존 조치 응답의 null 계약을 유지하며 생성 ID는 이력 조회에서 제공한다.
                nextActionSeq,
                fromStatus,
                toStatus,
                actionType.name(),
                reason,
                request.evidenceRef(),
                actionUserId,
                actionUserLoginId,
                actionAt
        );
    }

    private boolean isClosed(ExceptionStatus status) {
        return status == ExceptionStatus.RESOLVED || status == ExceptionStatus.REJECTED;
    }

    private String toJson(ExceptionAuditValue value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("예외 처리 감사값 직렬화에 실패했습니다.", exception);
        }
    }

    private record ExceptionAuditValue(
            ExceptionStatus status,
            Long assignedTo,
            ExceptionActionType actionType,
            String evidenceRef
    ) {
    }
}
