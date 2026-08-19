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
     * before/after JSON 을 리프 경로("payment.amount", "attributions[0].contractId") 단위로
     * 펴서 나란히 비교한다 — 최상위 키만 비교하면 중첩 객체가 통째로 한 칸이 되어
     * "바뀐 칸만 노랗게"(화면정의서 :1537)가 필드 단위로 동작하지 않는다.
     * 값이 있는 쪽이 하나라도 JSON 객체가 아니면(스칼라·배열·파싱 실패) 필드 비교 대신
     * 원문 한 줄 비교로 되돌린다 — 파싱 실패를 "값 없음"으로 취급하면 그쪽 원문이 diff 에서
     * 사라진다. 감사행은 이미 저장된 증거라 여기서 예외를 던져 화면을 깨뜨리지 않는다.
     */
    private List<DiffEntry> diff(String beforeJson, String afterJson) {
        boolean beforePresent = beforeJson != null && !beforeJson.isBlank();
        boolean afterPresent = afterJson != null && !afterJson.isBlank();
        if (!beforePresent && !afterPresent) {
            return List.of();
        }
        Map<String, Object> before = beforePresent ? parseObject(beforeJson) : null;
        Map<String, Object> after = afterPresent ? parseObject(afterJson) : null;
        if ((beforePresent && before == null) || (afterPresent && after == null)) {
            return List.of(new DiffEntry("value", beforeJson, afterJson,
                    !Objects.equals(beforeJson, afterJson)));
        }

        Map<String, Object> beforeLeaves = new LinkedHashMap<>();
        Map<String, Object> afterLeaves = new LinkedHashMap<>();
        if (before != null) {
            flatten("", before, beforeLeaves);
        }
        if (after != null) {
            flatten("", after, afterLeaves);
        }

        Set<String> fields = new LinkedHashSet<>(beforeLeaves.keySet());
        fields.addAll(afterLeaves.keySet());

        List<DiffEntry> entries = new ArrayList<>(fields.size());
        for (String field : fields) {
            Object beforeValue = beforeLeaves.get(field);
            Object afterValue = afterLeaves.get(field);
            entries.add(new DiffEntry(
                    field.isEmpty() ? "value" : field,
                    displayValue(beforeValue),
                    displayValue(afterValue),
                    !Objects.equals(beforeValue, afterValue)
            ));
        }
        return entries;
    }

    /** 중첩 객체·배열을 리프 경로 맵으로 편다. 빈 컨테이너는 그 자체를 리프로 남긴다. */
    private static void flatten(String prefix, Object value, Map<String, Object> out) {
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                flatten(prefix.isEmpty() ? key : prefix + "." + key, entry.getValue(), out);
            }
            return;
        }
        if (value instanceof List<?> list && !list.isEmpty()) {
            for (int index = 0; index < list.size(); index++) {
                flatten(prefix + "[" + index + "]", list.get(index), out);
            }
            return;
        }
        out.put(prefix, value);
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
