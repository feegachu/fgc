package com.susukkang.fgc.common.code;

/**
 * cap_check.result_status 의 자바측 표현
 * DB CHECK (result_status IN ('NORMAL','WARNING','VIOLATION','REVIEW_REQUIRED')) 와 반드시 일치해야 함
 *
 * 판정 우선순위(위에서부터 순서대로 확인, 먼저 걸리는 조건이 최종 결과):
 * 1. REVIEW_REQUIRED — 룰셋 미분류 항목이나 환급률표 부재 등 사람 판단이 필요한 경우가 하나라도 있으면 최우선
 * 2. VIOLATION — 산입액이 한도를 실제로 초과
 * 3. WARNING — 아직 초과는 아니지만 사용률이 경고 기준(cap_rule_set.warning_usage_pct, 기본 90%) 이상
 * 4. NORMAL — 위 어디에도 해당하지 않는 정상 범위
 */
public enum CapResultStatus {
    NORMAL,
    WARNING,
    VIOLATION,
    REVIEW_REQUIRED
}
