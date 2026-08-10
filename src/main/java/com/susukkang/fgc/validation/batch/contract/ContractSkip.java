package com.susukkang.fgc.validation.batch.contract;

/** 계약 단위로 허용된 skip의 사유와 후속 예외 생성에 필요한 식별 정보다. */
public record ContractSkip(Long contractId, String reasonCode, String message) {
}
