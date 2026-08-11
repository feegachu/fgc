package com.susukkang.fgc.schedule.dto;

import jakarta.validation.constraints.NotNull;
import lombok.*;

/**
 * 설명 : 스케줄 재생성에 대한 응답
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-11
 */
@Builder
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleRegenResponse {
    Long scheduleHeaderId; //새 스케줄 헤더 ID
    Long scheduleVersionNo; //새 스케줄 번호
}