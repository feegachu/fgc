package com.susukkang.fgc.validation.dto;

import java.time.LocalDate;

/**
 * IF-API-46(가칭) 검증 실행 목록 검색조건
 */
public record ValidationRunSearchCriteria(
        LocalDate month,
        String status
) {
}
