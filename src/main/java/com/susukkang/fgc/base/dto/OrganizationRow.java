package com.susukkang.fgc.base.dto;

import java.time.LocalDate;

/**
 * 조직 조회 SQL 결과를 서비스 계층으로 전달하는 내부 프로젝션이다.
 */
public record OrganizationRow(
        Long organizationId,
        String organizationCode,
        String organizationName,
        String organizationType,
        Long parentId,
        String parentName,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        boolean activeYn
) {
}
