package com.susukkang.fgc.exceptioncase.dto;

import java.math.BigDecimal;

/** 지급 수정 후에도 남은 동일 업무키의 대사 차이. */
public record ExceptionReconciliationIssue(Long resultId, String resultType, String resultLabel,
        BigDecimal expectedAmount, BigDecimal actualAmount, BigDecimal differenceAmount,
        Integer installmentNo, String expectedAgent, String actualAgent, String sourceLink) {}
