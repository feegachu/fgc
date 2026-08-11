package com.susukkang.fgc.validation.batch;

import com.susukkang.fgc.validation.dto.MonthlyValidationJobParameters;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;

/**
 * IF-BAT-01 JobParameters 계약을 Job 시작 전에 강제
 */
public class MonthlyValidationJobParametersValidator implements JobParametersValidator {

    @Override
    public void validate(JobParameters parameters) throws JobParametersInvalidException {
        if (parameters == null) {
            throw new JobParametersInvalidException("JobParameters가 없습니다.");
        }
        // 실제 검증 규칙은 다시 안 적고 MonthlyValidationJobParameters.from() 재사용
        try {
            MonthlyValidationJobParameters.from(parameters);
        } catch (IllegalArgumentException e) {
            throw new JobParametersInvalidException(e.getMessage());
        }
    }
}
