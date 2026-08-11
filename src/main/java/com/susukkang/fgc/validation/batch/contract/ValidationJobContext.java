package com.susukkang.fgc.validation.batch.contract;

import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.validation.dto.MonthlyValidationJobParameters;

import java.time.LocalDate;
import java.util.Objects;

/** MonthlyValidationJob의 JobParameter를 도메인 포트에 전달하는 불변 컨텍스트다. */
public record ValidationJobContext(
        LocalDate validationMonth,
        long runNo,
        ValidationRunType runType,
        long triggeredBy,
        String requestId
) {
    public ValidationJobContext {
        Objects.requireNonNull(validationMonth, "validationMonth는 필수입니다.");
        if (validationMonth.getDayOfMonth() != 1) {
            throw new IllegalArgumentException("validationMonth는 해당 월의 첫날이어야 합니다.");
        }
        if (runNo <= 0) {
            throw new IllegalArgumentException("runNo는 1 이상이어야 합니다.");
        }
        Objects.requireNonNull(runType, "runType은 필수입니다.");
        if (triggeredBy <= 0) {
            throw new IllegalArgumentException("triggeredBy는 1 이상이어야 합니다.");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId는 필수입니다.");
        }
    }

    /**
     * MonthlyValidationJobParameters(JobParameters를 파싱한 결과)를 그대로 옮겨 담음
     */
    public static ValidationJobContext from(MonthlyValidationJobParameters parameters) {
        return new ValidationJobContext(
                parameters.validationMonth(), parameters.runNo(), parameters.runType(),
                parameters.triggeredBy(), parameters.requestId());
    }
}
