package com.susukkang.fgc.contract.dto;

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

    private String contractNo;
    private Long insurerId;
    private Long productOfferingId;
    private Long agentId;
    private String currentStatus;
    private LocalDate contractDateFrom;
    private LocalDate contractDateTo;
}