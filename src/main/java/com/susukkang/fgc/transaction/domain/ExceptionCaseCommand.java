package com.susukkang.fgc.transaction.domain;

import lombok.Builder;
import lombok.Getter;

/**
 * 설명 : 지급 건 한도 검증 예외 저장 명령
 *
 * @author yslee
 * @since 2026-08-06
 * @version 1.2
 */
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
    private final String sourceEntityId;
    private final String title;
    private final String description;
}
