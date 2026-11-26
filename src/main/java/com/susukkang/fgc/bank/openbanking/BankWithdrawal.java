package com.susukkang.fgc.bank.openbanking;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 거래내역조회의 출금 1건. 상대 계좌번호는 응답에 없어 통장인자내용(printContent)으로만 누구에게 보냈는지 안다. */
public record BankWithdrawal(LocalDate tranDate, String tranTime, String printContent, BigDecimal amount) {
}
