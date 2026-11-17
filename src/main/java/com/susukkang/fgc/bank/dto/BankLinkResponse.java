package com.susukkang.fgc.bank.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/** IF-API-95 은행 연결 상태. 계좌번호는 은행이 가려서 준 값만 있다. */
public record BankLinkResponse(
        @Schema(description = "수수료 지급 계좌를 연결한 적이 있는지") boolean linked,
        @Schema(description = "지금 은행을 부를 수 있는지. 서버를 다시 띄우면 false 가 되어 다시 연결해야 한다") boolean connected,
        String bankName,
        @Schema(description = "은행이 가려서 준 계좌번호") String accountNumMasked,
        String accountHolderName,
        OffsetDateTime linkedAt,
        @Schema(description = "저장소에 들어 있는 모의 은행에 연결되어 있는지") boolean mock) {
}
