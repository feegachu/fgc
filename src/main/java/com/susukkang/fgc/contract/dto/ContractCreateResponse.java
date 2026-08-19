package com.susukkang.fgc.contract.dto;

import lombok.Builder;

import java.util.List;

@Builder
public record ContractCreateResponse(
        Long contractId,
        List<Long> scheduleHeaderIds
) {
}
