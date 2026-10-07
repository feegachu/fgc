package com.susukkang.fgc.audit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.audit.dto.AuditDiffEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * AUDT-W01 변경 내용 비교. 1차 AuditLogViewController 에 있던 계산을 옮겨
 * 서버 렌더링 화면과 상세 API(#407)가 같은 결과를 쓰게 한다.
 */
@Component
@RequiredArgsConstructor
public class AuditDiffCalculator {

    private final ObjectMapper objectMapper;

    /**
     * before/after JSON 을 리프 경로("payment.amount", "attributions[0].contractId") 단위로
     * 펴서 나란히 비교한다 — 최상위 키만 비교하면 중첩 객체가 통째로 한 칸이 되어
     * "바뀐 칸만 노랗게"(화면정의서 :1537)가 필드 단위로 동작하지 않는다.
     * 값이 있는 쪽이 하나라도 JSON 객체가 아니면(스칼라·배열·파싱 실패) 필드 비교 대신
     * 원문 한 줄 비교로 되돌린다 — 파싱 실패를 "값 없음"으로 취급하면 그쪽 원문이 diff 에서
     * 사라진다. 감사행은 이미 저장된 증거라 여기서 예외를 던져 화면을 깨뜨리지 않는다.
     */
    public List<AuditDiffEntry> diff(String beforeJson, String afterJson) {
        boolean beforePresent = beforeJson != null && !beforeJson.isBlank();
        boolean afterPresent = afterJson != null && !afterJson.isBlank();
        if (!beforePresent && !afterPresent) {
            return List.of();
        }
        Map<String, Object> before = beforePresent ? parseObject(beforeJson) : null;
        Map<String, Object> after = afterPresent ? parseObject(afterJson) : null;
        if ((beforePresent && before == null) || (afterPresent && after == null)) {
            return List.of(new AuditDiffEntry("value", beforeJson, afterJson,
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

        List<AuditDiffEntry> entries = new ArrayList<>(fields.size());
        for (String field : fields) {
            Object beforeValue = beforeLeaves.get(field);
            Object afterValue = afterLeaves.get(field);
            entries.add(new AuditDiffEntry(
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
