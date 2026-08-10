package com.susukkang.fgc.validation.dto;

import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.common.util.DateUtil;
import org.springframework.batch.core.JobParameters;

import java.time.DateTimeException;
import java.time.LocalDate;

/**
 * IF-BAT-01 MonthlyValidationJob의 JobParameters 계약
 */
public record MonthlyValidationJobParameters(
        LocalDate validationMonth,
        Long runNo,
        ValidationRunType runType,
        Long triggeredBy,
        String requestId
) {

    public static MonthlyValidationJobParameters from(JobParameters jobParameters) {
        // validationMonth: ValidationRunController#create가 request body의 validationMonth를
        // 파싱할 때와 완전히 같은 DateUtil.parseSettlementMonth 사용
        String validationMonthRaw = jobParameters.getString("validationMonth");
        LocalDate validationMonth;
        try {
            validationMonth = DateUtil.parseSettlementMonth(validationMonthRaw);
        } catch (DateTimeException | NullPointerException e) {
            throw new IllegalArgumentException("validationMonth는 yyyy-MM 형식이어야 합니다: " + validationMonthRaw, e);
        }

        // runNo: JobParameters.getLong()은 키가 없으면 예외 대신 null을 돌려줌
        // -> 형식 오류가 아니라 "필수값 누락"은 null 체크로 걸러야 함
        Long runNo = jobParameters.getLong("runNo");
        if (runNo == null || runNo <= 0 || runNo > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("runNo는 1 이상의 정수여야 합니다: " + runNo);
        }

        // runType: ValidationRunType.valueOf(String)은 enum에 없는 문자열이면
        // IllegalArgumentException을, null을 넘기면 NullPointerException을 던짐
        String runTypeRaw = jobParameters.getString("runType");
        ValidationRunType runType;
        try {
            runType = ValidationRunType.valueOf(runTypeRaw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("runType 값이 올바르지 않습니다: " + runTypeRaw, e);
        }

        // triggeredBy: 형식 검증이 필요 없는 단순 필수값이라 null 체크만
        Long triggeredBy = jobParameters.getLong("triggeredBy");
        if (triggeredBy == null) {
            throw new IllegalArgumentException("triggeredBy는 필수입니다.");
        }

        // requestId: 2-5절 헤더(X-Request-Id)와 같은 값을 배치에도 남겨서, 사람이 호출한
        // API 요청과 그 뒤에 돈 배치 실행을 하나의 요청 흐름으로 추적할 수 있게함
        String requestId = jobParameters.getString("requestId");
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId는 필수입니다.");
        }

        return new MonthlyValidationJobParameters(validationMonth, runNo, runType, triggeredBy, requestId);
    }
}
