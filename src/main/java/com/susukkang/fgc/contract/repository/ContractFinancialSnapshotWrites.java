package com.susukkang.fgc.contract.repository;

/** 계약 잠금 아래 수기 합성 초회 스냅샷의 최신값을 멱등 추가한다. */
public interface ContractFinancialSnapshotWrites {
    int appendManualInitialIfChanged(Long contractId);
}
