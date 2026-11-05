package com.susukkang.fgc.acquisitioncost.dto;

import com.susukkang.fgc.common.code.PaymentStage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** FUN-062 검증 요청. 금액·요율은 받지 않고 서버가 정책·룰셋·예정 스케줄에서 읽는다. */
public record AcquisitionCostCheckRequest(
        @NotNull @Positive Long contractId,
        @Schema(description = "검증할 지급단계. 두 단계의 한도 검증을 합치지 않는다.")
        @NotNull PaymentStage paymentStage
) {
}
