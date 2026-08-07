package com.susukkang.fgc.contract.dto;

import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.contract.domain.ContractStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
/**
 * 설명 : 계약 데이터 조회조건 DTO
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-05
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ContractSearchCondition {

    private String contractNo; //계약번호
    private Long insurerId; //보험사 ID
    private Long productOfferingId; //상품
    private Long agentId; //FC ID
    private CapResultStatus capResultStatus; //1,200% 한도 판정
    private ContractStatus currentStatus; //계약상태
    private LocalDate contractDateFrom; //계약일 시작
    private LocalDate contractDateTo; //계약일 끝
}
