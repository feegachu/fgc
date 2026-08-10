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
        Long triggeredBy
) {
}
