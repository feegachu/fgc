package com.susukkang.fgc.contract.dto;

import lombok.Builder;

import java.util.List;

@Builder
public record ContractUpdateResponse(
        Long contractId,
        List<Long> regeneratedScheduleIds
) {
}
