package com.susukkang.fgc.transaction.domain;

import java.time.LocalDate;

public record ContractReference(
        Long contractId,
        Long agentId,
        LocalDate contractDate
) {
}
