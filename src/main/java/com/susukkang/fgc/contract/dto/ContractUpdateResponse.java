package com.susukkang.fgc.contract.dto;

import lombok.Builder;

import java.time.OffsetDateTime;
import java.util.List;

/** IF-API-19 응답. 200은 계약·사건 저장 성공이며 후속 처리 완료를 뜻하지 않는다(followUp). */
@Builder
public record ContractUpdateResponse(
        Long contractId,
        List<Long> scheduleHeaderIds,
        List<Long> regeneratedScheduleIds,
        OffsetDateTime updatedAt,
        Long statusEventId,
        FollowUp followUp
) {
    /** 상태 사건 후속 작업 상태. 처리기가 한 트랜잭션에서 끝내므로 처리 중(PROCESSING)은 밖에 보이지 않는다. */
    public record FollowUp(Status status, Long eventId) {
        public static final FollowUp NOT_REQUIRED = new FollowUp(Status.NOT_REQUIRED, null);

        public enum Status { PENDING, SUCCEEDED, FAILED, NOT_REQUIRED }
    }
}
