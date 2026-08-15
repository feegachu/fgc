package com.susukkang.fgc.base.dto;

import java.time.LocalDate;
import java.util.Objects;

public record OrganizationSearchCriteria(
        String keyword,
        LocalDate asOf
) {
    public OrganizationSearchCriteria {
        Objects.requireNonNull(asOf, "asOf는 null일 수 없습니다.");
    }
}
