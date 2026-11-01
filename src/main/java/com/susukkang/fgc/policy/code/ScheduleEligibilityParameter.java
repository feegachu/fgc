package com.susukkang.fgc.policy.code;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 설명 : FUN-015 SCHEDULE_ELIGIBILITY 정책 파라미터 레지스트리(인터페이스정의서 §9-5, 운영정책서 제48조).
 * 키마다 판단 코드(ContractStatusPaymentRule)가 구현한 값 하나만 받는다. 값을 바꾸면 그 판단도 함께 바꾼다(COR-004).
 *
 * @author yslee
 * @version 1.0
 * @since 2026-11-01
 */
public enum ScheduleEligibilityParameter {
    STATUS_MONTH_RULE("PAY_IF_ACTIVE_ON_DUE_DATE"),
    PRORATION_RULE("NO_PRORATION"),
    REVIVAL_BACKFILL_RULE("NO_RETROACTIVE");

    public static final Set<String> KEYS =
            Arrays.stream(values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());

    private final String implementedValue;

    ScheduleEligibilityParameter(String implementedValue) {
        this.implementedValue = implementedValue;
    }

    /** 레지스트리의 키이고 값이 구현한 문자열일 때만 참이다. 자유 조건식이나 다른 타입은 받지 않는다. */
    public static boolean accepts(String key, JsonNode value) {
        return value != null && value.isTextual() && Arrays.stream(values())
                .anyMatch(parameter -> parameter.name().equals(key) && parameter.implementedValue.equals(value.textValue()));
    }
}
