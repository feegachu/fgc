package com.susukkang.fgc.schedule.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 예상 스케줄 상세 조회 응답 DTO
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-09
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleDetailResponse {
    private ScheduleHeaderResponse header; // 스케줄 헤더
    private List<ScheduleLineResponse> schedules; // 회차별 예상 스케줄 목록
}
