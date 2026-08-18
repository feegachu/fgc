package com.susukkang.fgc.contract.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
/**
 * 설명 : 계약데이터의 응답 DTO
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-05
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractResponse {
    private Long contractId;
    private List<Long> scheduleHeaderIds;
    private List<Long> regeneratedScheduleIds;
}
