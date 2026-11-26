package com.susukkang.fgc.bank.service;

import java.time.LocalDate;

/**
 * 설명 : 지급 이체 대사가 은행에 그 정산월의 거래내역을 묻기 직전에 내는 사건.
 * 모의 은행이 받아 그 달의 이체를 준비한다. 실제 은행에 붙였을 때는 받는 곳이 없다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-11-18
 */
public record BankInquiryStarting(LocalDate settlementMonth) {
}
