package com.susukkang.fgc.contract.dto;

import com.susukkang.fgc.common.code.ReasonCode;
import com.susukkang.fgc.contract.domain.ContractStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 설명 : ContractStatusEventResponse
 * 계약 상세보기 -> 계약상태 사건 이력 DTO
 * ResponseContractDetail의 필드로 들어감
 * @author hjKang
 * @version 1.0
 * @since 2026-08-05
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractStatusEventResponse {

    //계약상태 사건 이력
    private Integer eventSeq; //순번
    private ContractStatus previousStatus; //이전 상태
    private ContractStatus newStatus; //새 상태
    private OffsetDateTime effectiveAt; //효력 일
    private OffsetDateTime receivedAt; //수신 일
    private OffsetDateTime processedAt;//처리 일
    private ReasonCode reasonCode; //사유
}