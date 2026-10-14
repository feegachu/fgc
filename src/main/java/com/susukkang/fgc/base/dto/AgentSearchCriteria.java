package com.susukkang.fgc.base.dto;

import java.time.LocalDate;
import java.util.Objects;

/** IF-API-07 설계사 기준정보 조회 조건이다. */
public record AgentSearchCriteria(
        Long organizationId,
        String keyword,
        LocalDate asOf,
        boolean includeTerminated,
        boolean includeHistorical
) {
    /** 기존 기준정보·등록 화면은 기준일에 위촉 기간이 유효한 설계사만 조회한다. */
    public AgentSearchCriteria(Long organizationId, String keyword, LocalDate asOf) {
        this(organizationId, keyword, asOf, false);
    }

    /** 계약 목록 필터용 이력 모드는 기존 호출에서 활성화하지 않는다. */
    public AgentSearchCriteria(Long organizationId, String keyword, LocalDate asOf, boolean includeTerminated) {
        this(organizationId, keyword, asOf, includeTerminated, false);
    }

    public AgentSearchCriteria {
        Objects.requireNonNull(asOf, "asOf는 null일 수 없습니다.");
    }
}
