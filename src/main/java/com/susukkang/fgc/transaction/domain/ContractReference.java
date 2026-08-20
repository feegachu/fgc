package com.susukkang.fgc.transaction.domain;

import java.time.LocalDate;

/**
 * 설명 : 지급 귀속 검증용 계약 참조 정보
 *
 * @author yslee
 * @since 2026-08-05
 * @version 1.2
 */
public record ContractReference(
        Long contractId,
        Long agentId,
        Long organizationId,
        LocalDate contractDate
) {
}
