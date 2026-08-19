package com.susukkang.fgc.validation.event;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 설명 : IF-EVT-07 검증 실행 확정 이벤트
 *
 * @author yslee
 * @since 2026-08-19
 * @version 1.2
 */
public record ValidationRunFinalized(
        Long validationRunId,
        LocalDate validationMonth,
        Long finalizedBy,
        OffsetDateTime finalizedAt
) {
    public ValidationRunFinalized {
        Objects.requireNonNull(validationRunId, "validationRunId는 필수입니다.");
        Objects.requireNonNull(validationMonth, "validationMonth는 필수입니다.");
        Objects.requireNonNull(finalizedBy, "finalizedBy는 필수입니다.");
        Objects.requireNonNull(finalizedAt, "finalizedAt은 필수입니다.");
    }
}
