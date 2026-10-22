package com.susukkang.fgc.base.dto;

import java.time.LocalDate;
import java.util.Objects;

public record OrganizationSearchCriteria(
        String keyword,
        LocalDate asOf,
        boolean includeHistorical
) {
    /** 계약 목록 필터용 이력 모드는 기존 호출에서 활성화하지 않는다. */
    public OrganizationSearchCriteria(String keyword, LocalDate asOf) {
        this(keyword, asOf, false);
    }

    public OrganizationSearchCriteria {
        Objects.requireNonNull(asOf, "asOf는 null일 수 없습니다.");
    }
}
