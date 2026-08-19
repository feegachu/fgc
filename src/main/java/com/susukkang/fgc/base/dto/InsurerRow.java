package com.susukkang.fgc.base.dto;

/**
 * 보험회사 조회 SQL 결과를 서비스 계층으로 전달하는 내부 프로젝션이다.
 */
public record InsurerRow(
        Long insurerId,
        String insurerCode,
        String insurerName,
        String insurerType,
        boolean activeYn
) {
}
