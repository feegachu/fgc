package com.susukkang.fgc.base.dto;

import java.time.LocalDate;
import java.util.Objects;

/** 상품 판매버전 조회 조건. sort 는 {@code key,asc|desc} 형식이며(#604) 비어 있으면 상품코드·판매버전 순이다. */
public record ProductSearchCriteria(
        long insurerId,
        LocalDate asOf,
        boolean includeExpired,
        boolean includeHistorical,
        String sort,
        String keyword,
        Long selectedId
) {
    public ProductSearchCriteria(long insurerId, LocalDate asOf, boolean includeExpired, boolean includeHistorical, String sort) {
        this(insurerId, asOf, includeExpired, includeHistorical, sort, null, null);
    }
    /** 기존 기준정보·계약 입력은 기준일에 판매 중인 버전만 조회한다. */
    public ProductSearchCriteria(long insurerId, LocalDate asOf) {
        this(insurerId, asOf, false);
    }

    /** 계약 목록 필터용 이력 모드는 기존 호출에서 활성화하지 않는다. */
    public ProductSearchCriteria(long insurerId, LocalDate asOf, boolean includeExpired) {
        this(insurerId, asOf, includeExpired, false);
    }

    /** 정렬 없이 조회하는 기존 호출용 생성자. */
    public ProductSearchCriteria(long insurerId, LocalDate asOf, boolean includeExpired, boolean includeHistorical) {
        this(insurerId, asOf, includeExpired, includeHistorical, null);
    }

    public ProductSearchCriteria {
        Objects.requireNonNull(asOf, "asOf는 null일 수 없습니다.");
    }
}
