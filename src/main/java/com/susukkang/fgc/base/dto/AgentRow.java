package com.susukkang.fgc.base.dto;

import java.time.LocalDate;

/** 설계사 조회 SQL 결과를 서비스 계층으로 전달하는 내부 프로젝션이다. */
public record AgentRow(
        Long agentId,
        String agentCode,
        String agentName,
        String rankCode,
        Long organizationId,
        String organizationCode,
        String organizationName,
        LocalDate appointmentDate,
        LocalDate terminationDate,
        String agentStatus,
        LocalDate latestRegistrationDate,
        Boolean priorThreeYearExperienceYn,
        LocalDate experienceCheckedOn,
        boolean newcomerSupportEligibleYn,
        LocalDate newcomerSupportEndDate,
        boolean activeYn
) {
}
