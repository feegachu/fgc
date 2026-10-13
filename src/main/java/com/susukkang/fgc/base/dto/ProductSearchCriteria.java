package com.susukkang.fgc.base.dto;

import java.time.LocalDate;
import java.util.Objects;

public record ProductSearchCriteria(
        long insurerId,
        LocalDate asOf,
        boolean includeExpired
) {
    /** 기존 기준정보·계약 입력은 기준일에 판매 중인 버전만 조회한다. */
    public ProductSearchCriteria(long insurerId, LocalDate asOf) {
        this(insurerId, asOf, false);
    }

    public ProductSearchCriteria {
        Objects.requireNonNull(asOf, "asOf는 null일 수 없습니다.");
    }
}
