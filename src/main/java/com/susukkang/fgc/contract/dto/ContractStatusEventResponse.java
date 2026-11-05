package com.susukkang.fgc.contract.dto;

import com.susukkang.fgc.common.code.ContractStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * IF-API-16 계약 상태 변경 이력 응답. registeredBy는 원천에 등록자가 없으면 null이다(현재 사용자로 채우지 않는다).
 * 수기 등록이 아닌 사건은 reasonCode·reason도 null일 수 있다. recordedAt은 시스템이 사건을 기록한 시각이고,
 * Job별 처리 시각은 processings, 후속 작업의 현재 상태는 followUp에 있다.
 */
public record ContractStatusEventResponse(
        int eventSeq,
        ContractStatus previousStatus,
        ContractStatus newStatus,
        OffsetDateTime effectiveAt,
        OffsetDateTime receivedAt,
        List<ContractStatusEventProcessingResponse> processings,
        String sourceSystem,
        String sourceEventKey,
        Long eventId,
        @Schema(nullable = true) String reasonCode,
        @Schema(nullable = true) String reason,
        @Schema(nullable = true) RegisteredBy registeredBy,
        OffsetDateTime recordedAt,
        EventFollowUp followUp
) {
    public ContractStatusEventResponse {
        processings = List.copyOf(processings);
    }

    public String getPreviousStatusLabel() {
        return previousStatus == null ? null : previousStatus.label();
    }

    public String getNewStatusLabel() {
        return newStatus == null ? null : newStatus.label();
    }

    public record RegisteredBy(Long userId, String loginId, String userName) {
    }

    /** 작업이 여럿이면 앞선 값이 사건의 상태다. 소진: 자동 재시도를 다 쓴 작업. 처리 중: 잡은 뒤 아직 결과가 없는 작업. */
    public enum FollowUpStatus { EXHAUSTED, FAILED, PROCESSING, PENDING, SUCCEEDED, NOT_REQUIRED }

    /** processings는 끝난 시도의 이력이라 대기·소진을 담지 못한다. 작업이 없는 사건은 NOT_REQUIRED다(후속 처리 대상 아님). */
    public record EventFollowUp(FollowUpStatus status, List<FollowUpJob> jobs) {
        public static EventFollowUp of(List<FollowUpJob> jobs) {
            return new EventFollowUp(
                    jobs.stream().map(FollowUpJob::status).min(Comparator.naturalOrder()).orElse(FollowUpStatus.NOT_REQUIRED),
                    List.copyOf(jobs));
        }
    }

    /** nextRetryAt은 자동 재시도가 남은 작업에만 있다. */
    public record FollowUpJob(
            String job,
            FollowUpStatus status,
            int attemptCount,
            int maxAttempts,
            @Schema(nullable = true) OffsetDateTime lastAttemptAt,
            @Schema(nullable = true) OffsetDateTime nextRetryAt,
            @Schema(nullable = true) String errorCode
    ) {
    }
}
