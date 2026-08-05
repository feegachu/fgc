package com.susukkang.fgc.transaction.domain;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ExceptionCaseCommand {
    private final String exceptionKey;
    private final String exceptionType;
    private final String severity;
    private final Long contractId;
    private final Long agentId;
    private final Long policyVersionId;
    private final Long paymentId;
    private final String title;
    private final String description;
}
