package com.susukkang.fgc.validation.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * FGC-FUN-043 설계사 단위 1,200% 모니터링 지표 1행 — 참고용 집계다.
 * 계약별 규제판정(cap_check.result_status)을 이 집계가 바꾸거나 덮어쓰지 않는다.
 *
 * paymentStage로 반드시 나눠야 한다(코드리뷰 반영) — 원수사→GA(INSURER_TO_GA)와
 * GA→설계사(GA_TO_FC) 1,200%는 계산 규칙 자체가 다른 별도 게이지라 하나로 합치면
 * 절대 금지사항 위반이다(docs/07_규제조문표_v0.2.1.md "보험회사→GA와 GA→설계사 1,200%
 * 검증을 하나의 게이지로 합치지 않는다", 같은 문서 "1,200% 게이지 분리" 절;
 * 화면정의서 v1.0→v2.0 변경이력에도 "1,200% 게이지 2개로 분리"가 명시돼 있다).
 */
@Getter
@Setter
public class AgentCapMonitoringRow {
    private Long agentId;
    private String agentName;
    private String paymentStage;
    private long checkedCount;
    private long violationCount;
    private long warningCount;
    private long reviewRequiredCount;
}
