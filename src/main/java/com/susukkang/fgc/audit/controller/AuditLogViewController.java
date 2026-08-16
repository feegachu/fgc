package com.susukkang.fgc.audit.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.audit.dto.AuditLogResponse;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
    private final ObjectMapper objectMapper;

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
                ? List.<DiffEntry>of()
                : diff(selectedLog.beforeValue(), selectedLog.afterValue()));
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

    /** 변경 내용 비교 패널의 한 행. changed 인 칸만 노랗게 칠한다. */
    public record DiffEntry(String field, String before, String after, boolean changed) {
    }

    /**
     * before/after JSON 을 최상위 키 기준으로 나란히 비교한다.
     * JSON 객체가 아니면(스칼라·배열·파싱 실패) 원문 한 줄로 비교한다 — 감사행은 이미 저장된
     * 증거라 여기서 예외를 던져 화면을 깨뜨리지 않는다.
     */
    private List<DiffEntry> diff(String beforeJson, String afterJson) {
        Map<String, Object> before = parseObject(beforeJson);
        Map<String, Object> after = parseObject(afterJson);
        if (before == null && after == null) {
            if (beforeJson == null && afterJson == null) {
                return List.of();
            }
            return List.of(new DiffEntry("value", beforeJson, afterJson,
                    !Objects.equals(beforeJson, afterJson)));
        }

        Set<String> fields = new LinkedHashSet<>();
        if (before != null) {
            fields.addAll(before.keySet());
        }
        if (after != null) {
            fields.addAll(after.keySet());
        }

        List<DiffEntry> entries = new ArrayList<>(fields.size());
        for (String field : fields) {
            Object beforeValue = before == null ? null : before.get(field);
            Object afterValue = after == null ? null : after.get(field);
            entries.add(new DiffEntry(
                    field,
                    displayValue(beforeValue),
                    displayValue(afterValue),
                    !Objects.equals(beforeValue, afterValue)
            ));
        }
        return entries;
    }

    private Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private String displayValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }
}
