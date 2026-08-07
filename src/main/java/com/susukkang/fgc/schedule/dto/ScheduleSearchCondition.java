package com.susukkang.fgc.schedule.dto;

import com.susukkang.fgc.schedule.domain.ScheduleRegime;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
// 검색 필터
public class ScheduleSearchCondition {
    private String contractNo;                // 계약번호
    private String paymentStage;              // 지급단계
    private ScheduleRegime scheduleRegime;    // 적용체계
    private String schedulePurpose;           // 용도
    private String status;                    // 스케줄 상태
}
