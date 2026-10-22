package com.susukkang.fgc.common.web;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;

import java.util.Map;
import java.util.Set;

/**
 * 목록 API의 {@code sort=key,asc|desc} 파라미터(#604).
 *
 * <p>key 는 화면과 약속한 이름이고, 실제 컬럼·표현식으로의 매핑은 각 목록의 저장소가 맡는다.
 * 여기서는 허용 목록에 없는 key·방향을 COMMON_002 로 거부해, 사용자 입력이 ORDER BY 로 흘러가지 않게 한다.
 */
public record ListSort(String key, boolean ascending) {

    public String direction() {
        return ascending ? "asc" : "desc";
    }

    /** PageResponse.sort 에 그대로 쓰는 표기 */
    public String value() {
        return key + "," + direction();
    }

    /** 값이 없으면 기본 정렬을 쓰고, 있으면 허용 key 인지 검사한다. */
    public static ListSort parse(String raw, Set<String> allowedKeys, ListSort defaultSort) {
        if (raw == null || raw.isBlank()) {
            return defaultSort;
        }
        String[] parts = raw.split(",", -1);
        if (parts.length != 2) {
            throw invalid();
        }
        String key = parts[0].trim();
        String direction = parts[1].trim().toLowerCase();
        if (!allowedKeys.contains(key) || !(direction.equals("asc") || direction.equals("desc"))) {
            throw invalid();
        }
        return new ListSort(key, direction.equals("asc"));
    }

    private static FgcBusinessException invalid() {
        return new FgcBusinessException(FgcErrorCode.COMMON_002, "sort", Map.of("field", "sort"), null);
    }
}
