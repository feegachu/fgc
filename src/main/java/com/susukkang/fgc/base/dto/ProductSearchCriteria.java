package com.susukkang.fgc.base.dto;

import java.time.LocalDate;
import java.util.Objects;

public record ProductSearchCriteria(
        long insurerId,
        LocalDate asOf,
        boolean includeExpired,
        boolean includeHistorical
) {
    /** 기존 기준정보·계약 입력은 기준일에 판매 중인 버전만 조회한다. */
    public ProductSearchCriteria(long insurerId, LocalDate asOf) {
        this(insurerId, asOf, false);
    }

    /** 계약 목록 필터용 이력 모드는 기존 호출에서 활성화하지 않는다. */
    public ProductSearchCriteria(long insurerId, LocalDate asOf, boolean includeExpired) {
        this(insurerId, asOf, includeExpired, false);
    }

    public ProductSearchCriteria {
        Objects.requireNonNull(asOf, "asOf는 null일 수 없습니다.");
    }
}
