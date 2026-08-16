package com.susukkang.fgc.journal.event;

import java.util.Objects;

/**
 * IF-EVT-06. 월 배치 Step 6(journalPostingStep → imbalanceCheckStep)이 불균형 0건으로
 * 끝났을 때 발행된다("원장 → 대시보드"). Kafka도 MSA도 없다(COR-006) — 이 record를
 * ApplicationEventPublisher.publishEvent()로 발행하는 평범한 Spring ApplicationEvent다.
 * 이벤트에는 계산 결과(금액 등)를 담지 않고 ID·건수만 담는다(05_인터페이스정의서_v2_0.md:592
 * "이벤트에 금액 계산 결과를 담지 않는다") — 구독자는 이 값으로 다시 조회한다.
 * 구독자(대시보드)는 아직 없다 — 이 이슈는 발행측 계약만 정의한다.
 */
public record SettlementPosted(Long validationRunId, long journalCount, long imbalanceCount) {
    public SettlementPosted {
        Objects.requireNonNull(validationRunId, "validationRunId는 필수입니다.");
        if (journalCount < 0 || imbalanceCount < 0) {
            throw new IllegalArgumentException("건수는 음수일 수 없습니다.");
        }
    }
}
