package com.susukkang.fgc.contract.dto;

/** contract_event_job 1행의 현재 상태와 그 작업이 남긴 영향 건수. */
public record ContractEventJobRow(
        Long contractStatusEventId,
        ContractStatusEventResponse.FollowUpJob job,
        ContractStatusEventProcessingResponse.ProcessingSummary summary
) {
}
