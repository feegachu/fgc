package com.susukkang.fgc.exceptioncase.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.common.code.ExceptionActionType;
import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.common.code.ExceptionType;
import com.susukkang.fgc.audit.dto.AuditLogInsertRow;
import com.susukkang.fgc.audit.mapper.AuditLogMapper;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.common.web.RequestIdContext;
import com.susukkang.fgc.exceptioncase.dto.*;
import com.susukkang.fgc.exceptioncase.mapper.ExceptionCaseActionMapper;
import com.susukkang.fgc.exceptioncase.mapper.ExceptionCaseQueryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
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
    private static final String SORT = "severity,asc,createdAt,desc";

    private final ExceptionCaseQueryMapper exceptionCaseQueryMapper;
    private final ExceptionCaseActionMapper exceptionCaseActionMapper;
    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

    /**
     * 조건에 맞는 예외 한 페이지와 각 예외의 처리 이력을 조회한다.
     * 처리 이력은 페이지의 ID 목록으로 한 번에 조회하여 예외 건별 N+1 쿼리를 만들지 않는다.
     */
    @Transactional(readOnly = true)
    public ExceptionCaseSearchResponse search(ExceptionCaseSearchDTO criteria, int page, int size) {
        validatePage(page, size);
        List<ExceptionStatus> statuses = parseStatuses(criteria.getStatus());

        // 총 건수를 먼저 세어 범위 밖 page 를 마지막 페이지로 보정한다 — 큰 OFFSET 으로
        // 정렬·조인을 헛도는 행 조회를 아예 만들지 않는다. 응답의 page 가 보정된 값이다.
        long total = exceptionCaseQueryMapper.count(criteria, statuses);
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
                : exceptionCaseQueryMapper.search(criteria, statuses, offset, size);

        Map<Long, List<ExceptionActionResponse>> actionsByCaseId = loadActions(rows);
        Map<Long, List<ExceptionOccurrenceResponse>> occurrencesByCaseId = loadOccurrences(rows);
        List<ExceptionCaseResponseDTO> content = rows.stream()
                .map(row -> ExceptionCaseResponseDTO.from(
                        row,
                        occurrencesByCaseId.getOrDefault(row.exceptionCaseId(), List.of()),
                        actionsByCaseId.getOrDefault(row.exceptionCaseId(), List.of())))
                .toList();

        List<ExceptionTypeSummaryResponse> summary = exceptionCaseQueryMapper.countOpenByType()
                .stream()
                .map(ExceptionTypeSummaryResponse::from)
                .toList();

        PageResponse<ExceptionCaseResponseDTO> result =
                PageResponse.of(content, page, size, total, SORT);
        return new ExceptionCaseSearchResponse(
                summary, result.content(), result.page(), result.size(), result.totalElements(),
                result.totalPages(), result.sort());
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
        return exceptionCaseQueryMapper.findOccurrencesByCaseIds(exceptionCaseIds)
                .stream()
                .collect(Collectors.groupingBy(
                        ExceptionOccurrenceRow::exceptionCaseId,
                        Collectors.mapping(ExceptionOccurrenceResponse::from, Collectors.toList())
                ));
    }

    @Transactional(readOnly = true)
    public List<String> reasonCodes() {
        return exceptionCaseQueryMapper.findReasonCodes();
    }

    /** 담당자 필터 선택지 — 예외를 배정받은 적 있는 사용자만. */
    @Transactional(readOnly = true)
    public List<ExceptionAssigneeRow> assignees() {
        return exceptionCaseQueryMapper.findAssignees();
    }

    /** 검증월 필터 선택지 — 예외가 검출된 검증월만, 최신순. */
    @Transactional(readOnly = true)
    public List<LocalDate> validationMonths() {
        return exceptionCaseQueryMapper.findValidationMonths();
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
        return exceptionCaseQueryMapper.findActionsByCaseIds(exceptionCaseIds)
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

    private ExceptionActionResponse actionInternal(
            Long exceptionCaseId,
            ExceptionActionRequest request,
            Long actionUserId,
            String actionUserLoginId,
            boolean journalCorrectionExecuted) {

        if (request.reason() == null || request.reason().isBlank()) {
            throw new FgcBusinessException(FgcErrorCode.EXCP_001);
        }

        // 1. 동시에 같은 예외를 처리하지 못하도록 행 잠금
        ExceptionCaseActionTarget target =
                exceptionCaseActionMapper.findByIdForUpdate(exceptionCaseId);

        if (target == null) {
            throw new FgcBusinessException(
                    FgcErrorCode.COMMON_004,
                    "exceptionCaseId",
                    Map.of("field", "exceptionCaseId"),
                    null
            );
        }

        ExceptionStatus fromStatus = target.status();
        ExceptionActionType actionType = request.actionType();

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
                actionType.nextStatus(fromStatus);

        // 3. 잠금을 획득한 상태에서 다음 이력 순번 계산
        int nextActionSeq =
                exceptionCaseActionMapper.findNextActionSeq(exceptionCaseId);

        // 응답이 즉시 화면에 표시되므로 GET 조회 경로(DateUtil.toSeoul)와 같은 기준으로 맞춘다 — SIR-008
        OffsetDateTime actionAt = DateUtil.nowSeoul();

        // 4. 처리 이력 INSERT
        int inserted = exceptionCaseActionMapper.insertAction(
                ExceptionActionInsertCommand.builder()
                        .exceptionCaseId(exceptionCaseId)
                        .actionSeq(nextActionSeq)
                        .fromStatus(fromStatus)
                        .toStatus(toStatus)
                        .actionType(actionType)
                        .reason(request.reason())
                        .evidenceRef(request.evidenceRef())
                        .actionBy(actionUserId)
                        .actionAt(actionAt)
                        .build());
        if (inserted != 1) {
            throw new IllegalStateException("예외 처리 이력 저장에 실패했습니다.");
        }

        // 5. exception_case의 현재 상태 UPDATE
        Long assignedTo = actionType == ExceptionActionType.ASSIGN
                ? actionUserId
                : target.assignedTo();
        OffsetDateTime resolvedAt = isClosed(toStatus) ? actionAt : null;
        int updated = exceptionCaseActionMapper.updateCaseAfterAction(
                exceptionCaseId,
                toStatus,
                assignedTo,
                resolvedAt);

        if (updated != 1) {
            throw new IllegalStateException(
                    "예외 상태 변경에 실패했습니다."
            );
        }

        // 6. 감사로그 INSERT
        int audited = auditLogMapper.insert(
                AuditLogInsertRow.builder()
                        .actionCode("EXCEPTION_ACTION")
                        .entityType("EXCEPTION_CASE")
                        .entityId(String.valueOf(exceptionCaseId))
                        .userId(actionUserId)
                        .beforeValue(toJson(new ExceptionAuditValue(
                                fromStatus, target.assignedTo(), null, null)))
                        .afterValue(toJson(new ExceptionAuditValue(
                                toStatus, assignedTo, actionType, request.evidenceRef())))
                        .reason(request.reason())
                        .requestId(RequestIdContext.current())
                        .clientIp(null)
                        .build());
        if (audited != 1) {
            throw new IllegalStateException("예외 처리 감사로그 저장에 실패했습니다.");
        }

        return new ExceptionActionResponse(
                null, // Mapper에서 generated key를 받으면 exceptionActionId 설정
                nextActionSeq,
                fromStatus,
                toStatus,
                actionType.name(),
                request.reason(),
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
