package com.susukkang.fgc.report.dto;

import java.math.BigDecimal;

/**
 * 같은 필터의 전체 결과를 유형별로 합한 값. 서로 다른 성격의 금액이라 합산 순수령액은 만들지 않는다.
 * 환수 금액은 계산액이며 실제 회수 완료액이 아니다.
 */
public record StatementSummary(Bucket payment, Bucket held, Bucket adjustment, Bucket clawback) {

    public record Bucket(long count, BigDecimal amount) {
        public static Bucket empty() {
            return new Bucket(0, BigDecimal.ZERO);
        }
    }
}
