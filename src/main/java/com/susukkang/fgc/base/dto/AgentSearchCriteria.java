package com.susukkang.fgc.base.dto;

import java.time.LocalDate;
import java.util.Objects;

/** IF-API-07 설계사 기준정보 조회 조건이다. */
public record AgentSearchCriteria(
        Long organizationId,
        String keyword,
        LocalDate asOf
) {
    public AgentSearchCriteria {
        Objects.requireNonNull(asOf, "asOf는 null일 수 없습니다.");
    }
}
