package com.susukkang.fgc.bank.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** IF-API-96 은행 동의 화면 주소. 브라우저가 이 주소로 이동한다. */
public record BankAuthorizeUrlResponse(
        @Schema(description = "은행 동의 화면 주소. 10분 안에 동의를 마쳐야 한다") String authorizeUrl) {
}
