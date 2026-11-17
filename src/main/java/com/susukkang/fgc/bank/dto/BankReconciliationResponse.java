package com.susukkang.fgc.bank.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** IF-API-98·99 지급 이체 대사 결과. 어긋난 줄이 앞에 온다. */
public record BankReconciliationResponse(
        Long bankReconciliationRunId,
        LocalDate settlementMonth,
        @Schema(description = "은행에서 조회한 기간의 첫날(확정일)") LocalDate inquiryFrom,
        @Schema(description = "은행에서 조회한 기간의 끝날(확정일부터 31일, 오늘을 넘지 않음)") LocalDate inquiryTo,
        String bankName,
        String accountNumMasked,
        @Schema(description = "그 기간에 은행에서 받은 출금 건수") int withdrawalCount,
        @Schema(description = "대사를 돌린 사용자의 로그인 ID") String createdBy,
        OffsetDateTime createdAt,
        int matchedCount,
        int amountMismatchCount,
        int notTransferredCount,
        int unknownWithdrawalCount,
        List<Item> items) {

    // API 문서에서 다른 DTO의 Item 과 이름이 겹치지 않게 한다.
    @Schema(name = "BankReconciliationItem")
    public record Item(
            @Schema(description = "MATCHED 일치, AMOUNT_MISMATCH 금액 다름, NOT_TRANSFERRED 이체 없음, UNKNOWN_WITHDRAWAL 확인 안 된 출금")
            String result,
            String agentCode,
            String agentName,
            @Schema(description = "이체할 때 내 통장 표시에 적는 값. FGC + 정산월(yyMM) + - + 설계사 번호 6자리") String referenceCode,
            @Schema(description = "그 달 확정 지급 합계에서 확정 차감 합계를 뺀 금액(원 단위 반올림)") BigDecimal expectedAmount,
            @Schema(description = "같은 참조코드로 나간 출금 합계") BigDecimal bankAmount,
            @Schema(description = "은행 출금액에서 확정 지급 순액을 뺀 값") BigDecimal differenceAmount,
            LocalDate bankTranDate,
            @Schema(description = "은행 거래내역의 통장인자내용") String printContent) {
    }
}
