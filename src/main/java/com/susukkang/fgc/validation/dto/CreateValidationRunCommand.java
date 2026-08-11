package com.susukkang.fgc.validation.dto;

import com.susukkang.fgc.common.code.ValidationRunType;

import java.time.LocalDate;

/**
 * ValidationRunCreateService#create 입력
 * Controller가 CreateValidationRunRequest(String, String)를 파싱·검증해서 이 record로 바꿔 넘김
 */
public record CreateValidationRunCommand(
        LocalDate validationMonth,
        ValidationRunType runType,
        Long triggeredBy,
        Integer runNo
) {

    /**
     * 화면/API에서 실행을 생성할 때는 서비스가 다음 회차를 채번한다.
     * MonthlyValidationJob은 JobParameters의 runNo를 명시적으로 전달한다.
     */
    public CreateValidationRunCommand(
            LocalDate validationMonth,
            ValidationRunType runType,
            Long triggeredBy
    ) {
        this(validationMonth, runType, triggeredBy, null);
    }
}
