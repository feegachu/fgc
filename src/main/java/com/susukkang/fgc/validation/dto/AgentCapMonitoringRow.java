package com.susukkang.fgc.validation.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * FGC-FUN-043 설계사 단위 1,200% 모니터링 지표 1행 — 참고용 집계다.
 * 계약별 규제판정(cap_check.result_status)을 이 집계가 바꾸거나 덮어쓰지 않는다.
 */
@Getter
@Setter
public class AgentCapMonitoringRow {
    private Long agentId;
    private String agentName;
    private long checkedCount;
    private long violationCount;
    private long warningCount;
    private long reviewRequiredCount;
}
