package com.susukkang.fgc.base.dto;

import java.time.LocalDate;
import java.util.Objects;

public record ProductSearchCriteria(
        long insurerId,
        LocalDate asOf
) {
    public ProductSearchCriteria {
        Objects.requireNonNull(asOf, "asOf는 null일 수 없습니다.");
    }
}
