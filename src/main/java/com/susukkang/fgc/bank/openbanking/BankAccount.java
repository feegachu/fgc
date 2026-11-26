package com.susukkang.fgc.bank.openbanking;

/** 사용자정보조회(res_list)의 계좌 1건. 은행은 계좌번호를 가린 값으로만 준다. */
public record BankAccount(String fintechUseNum, String bankName, String accountNumMasked, String accountHolderName,
                          boolean inquiryAgreed) {
}
