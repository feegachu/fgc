package com.susukkang.fgc.transaction.domain;

/**
 * 설명 : 지급 건 귀속 계약의 계약번호 조회 결과 (IF-API-24 capPreview 표시용)
 *
 * @author yslee
 * @since 2026-08-16
 * @version 1.0
 */
public record AttributedContractNo(
        Long contractId,
        String contractNo
) {
}
