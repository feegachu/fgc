package com.susukkang.fgc.validation.batch.daily;

/**
 * ChangedContractItemProcessor → ChangedContractItemWriter로 전달되는 계약 1건 처리 결과
 */
public record ChangedContractResult(Long contractId, boolean success, String failureReason) {

    public static ChangedContractResult success(Long contractId) {
        return new ChangedContractResult(contractId, true, null);
    }

    public static ChangedContractResult dataQualitySkip(Long contractId, String failureReason) {
        return new ChangedContractResult(contractId, false, failureReason);
    }
}
