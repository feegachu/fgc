package com.susukkang.fgc.audit.controller;

import com.susukkang.fgc.audit.dto.AuditDiffEntry;
import com.susukkang.fgc.audit.dto.AuditLogResponse;
import com.susukkang.fgc.audit.service.AuditDiffCalculator;
import com.susukkang.fgc.audit.service.AuditLogQueryService;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.web.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.List;

/**
 * FGC-UI-AUDT-W01 감사로그 조회 화면 (FUN-061).
 * 인터페이스정의서 5-2 라우팅표: GET /audit-logs → audit/list.html, Ajax 없음 —
 * 목록·필터 선택지·변경 내용 비교(diff)까지 전부 서버 렌더링한다.
 * 행 선택은 selected 파라미터로 같은 검색조건을 유지한 채 재요청한다(화면정의서 §4-1 공통 규칙 7).
 *
 * AUDT-W01 은 다른 1차 화면과 달리 "전체 조회"가 아니다 — 화면정의서 :1530 권한
 * COMPLIANCE·SYSTEM_ADMIN. FUN-002 인수조건: 직접 URL 호출도 403 으로 차단된다.
 */
@Controller
@RequiredArgsConstructor
public class AuditLogViewController {

    /** 화면정의서 §4-1 공통 규칙 7 — 목록은 서버에서 20행씩. */
    private static final int PAGE_SIZE = 20;

    private final AuditLogQueryService auditLogQueryService;
    private final AuditDiffCalculator auditDiffCalculator;

    @PreAuthorize(Roles.CAN_VIEW_AUDIT_LOG)
    @GetMapping("/audit-logs")
    public String list(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(required = false) Long selected,
            Model model
    ) {
        // MPA @Controller 는 GlobalExceptionHandler(@RestController 한정) 밖이라 업무 예외가
        // 그대로 일반 500 화면이 된다. 사용자가 폼·URL 로 만들 수 있는 잘못된 값은
        // ExceptionCaseViewController 의 status 처럼 조용히 정상값으로 되돌려, "MPA 는 업무
        // 예외를 던지지 않는다"는 GlobalExceptionHandler 의 전제를 지킨다.
        if (from != null && to != null && from.isAfter(to)) {
            LocalDate swap = from;
            from = to;
            to = swap;
        }
        page = Math.max(1, Math.min(page, Integer.MAX_VALUE / PAGE_SIZE));

        PageResponse<AuditLogResponse> logs =
                auditLogQueryService.search(entityType, entityId, userId, action, from, to, page, PAGE_SIZE);

        model.addAttribute("logs", logs);
        model.addAttribute("form", new SearchForm(entityType, entityId, userId, action, from, to));
        model.addAttribute("actionCodes", auditLogQueryService.actionCodes());
        model.addAttribute("entityTypes", auditLogQueryService.entityTypes());
        model.addAttribute("auditUsers", auditLogQueryService.auditUsers());

        AuditLogResponse selectedLog = logs.content().stream()
                .filter(log -> log.auditLogId().equals(selected))
                .findFirst()
                .orElse(null);
        model.addAttribute("selectedLog", selectedLog);
        model.addAttribute("diffEntries", selectedLog == null
                ? List.<AuditDiffEntry>of()
                : auditDiffCalculator.diff(selectedLog.beforeValue(), selectedLog.afterValue()));
        return "audit/list";
    }

    /** 검색조건을 폼·행 링크·페이징 링크에 그대로 되돌리기 위한 뷰 전용 값. */
    public record SearchForm(
            String entityType,
            String entityId,
            Long userId,
            String action,
            LocalDate from,
            LocalDate to
    ) {
    }
}
