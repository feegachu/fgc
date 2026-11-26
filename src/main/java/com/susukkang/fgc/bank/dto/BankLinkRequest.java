package com.susukkang.fgc.bank.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** IF-API-97 은행이 동의 뒤 돌려준 값. */
public record BankLinkRequest(
        @Schema(description = "은행이 돌려준 인가 코드") @NotBlank @Size(max = 200) String code,
        @Schema(description = "IF-API-96 이 주소에 넣어 보낸 값") @NotBlank @Size(max = 64) String state) {
}
