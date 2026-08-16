package com.susukkang.fgc.schedule.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import java.util.List;

/**
 * 예상 스케줄 상세 조회 응답 DTO
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-09
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleDetailResponse {
    /** MyBatis가 헤더 1건과 라인 N건을 하나의 상세 응답으로 묶기 위한 루트 식별자. */
    @JsonIgnore
    private Long scheduleHeaderId;
    private ScheduleHeaderResponse header; // 스케줄 헤더
    private List<ScheduleLineResponse> schedules; // 회차별 예상 스케줄 목록
}
