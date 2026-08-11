package com.susukkang.fgc.validation.batch.daily;

import java.util.List;

/**
 * ChangedContractItemProcessor → ChangedContractItemWriter로 전달되는 계약 1건 처리 결과
 *
 * pendingEventIds를 여기 담아 함께 넘기는 이유(코드리뷰 반영, 2026-08-11): Writer가 자기
 * 시점에 findPendingEventIds를 다시 조회하면, Processor가 조회한 시점과 Writer가 실제
 * write()를 실행하는 시점(같은 청크의 다른 아이템을 처리하는 동안 시간이 흐름) 사이에 그
 * 계약에 새 contract_status_event가 들어왔을 때, Processor는 그 이벤트를 반영하지 않고
 * 결정(예: 스케줄 재생성 여부)했는데 Writer는 그 새 이벤트까지 포함해 SUCCEEDED로 찍어버리는
 * TOCTOU(time-of-check to time-of-use) 레이스가 생긴다 — 그러면 실제로는 재검증에 반영되지
 * 않은 이벤트가 "처리 완료"로 남아 다음 날 배치가 다시 잡지 못한다. Processor가 본 스냅샷을
 * 그대로 Writer에 넘기면 이 간극이 사라진다.
 */
public record ChangedContractResult(
        Long contractId,
        boolean success,
        String failureReason,
        List<Long> pendingEventIds
) {

    public static ChangedContractResult success(Long contractId, List<Long> pendingEventIds) {
        return new ChangedContractResult(contractId, true, null, pendingEventIds);
    }

    public static ChangedContractResult dataQualitySkip(Long contractId, String failureReason, List<Long> pendingEventIds) {
        return new ChangedContractResult(contractId, false, failureReason, pendingEventIds);
    }
}
