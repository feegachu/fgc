package com.susukkang.fgc.validation.dto;

import java.time.LocalDate;

/**
 * IF-API-46(가칭) 검증 실행 목록 검색조건
 *
 * <p>sort 는 {@code key,asc|desc} 형식이며(#604) 비어 있으면 검증월·회차 내림차순이다.
 */
public record ValidationRunSearchCriteria(
        LocalDate month,
        String status,
        String sort
) {

    /** 정렬 없이 검색하는 기존 호출용 생성자. */
    public ValidationRunSearchCriteria(LocalDate month, String status) {
        this(month, status, null);
    }
}
