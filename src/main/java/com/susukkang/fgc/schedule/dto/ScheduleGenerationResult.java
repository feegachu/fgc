package com.susukkang.fgc.schedule.dto;

import java.util.List;

/**
 * 계약 기반 예상 스케줄 생성 결과.
 * 실제로 새로 생성된 헤더 ID와 전체 라인 수를 함께 전달한다.
 *
 * @param scheduleHeaderIds 새로 생성된 스케줄 헤더 ID 목록
 * @param createdLineCount 새로 생성된 스케줄 라인 수
 */
public record ScheduleGenerationResult(
        List<Long> scheduleHeaderIds,
        int createdLineCount
) {
    public ScheduleGenerationResult {
        scheduleHeaderIds = List.copyOf(scheduleHeaderIds);
    }
}
