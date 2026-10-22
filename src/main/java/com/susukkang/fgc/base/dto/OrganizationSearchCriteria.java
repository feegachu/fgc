package com.susukkang.fgc.base.dto;

import java.time.LocalDate;
import java.util.Objects;

/** 조직 기준정보 조회 조건. sort 는 {@code key,asc|desc} 형식이며(#604) 비어 있으면 조직코드 오름차순이다. */
public record OrganizationSearchCriteria(
        String keyword,
        LocalDate asOf,
        boolean includeHistorical,
        String sort
) {
    /** 계약 목록 필터용 이력 모드는 기존 호출에서 활성화하지 않는다. */
    public OrganizationSearchCriteria(String keyword, LocalDate asOf) {
        this(keyword, asOf, false);
    }

    /** 정렬 없이 조회하는 기존 호출용 생성자. */
    public OrganizationSearchCriteria(String keyword, LocalDate asOf, boolean includeHistorical) {
        this(keyword, asOf, includeHistorical, null);
    }

    public OrganizationSearchCriteria {
        Objects.requireNonNull(asOf, "asOf는 null일 수 없습니다.");
    }
}
