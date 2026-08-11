package com.susukkang.fgc.schedule.dto;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.ScheduleHeaderStatus;
import com.susukkang.fgc.schedule.code.SchedulePurpose;
import com.susukkang.fgc.schedule.code.ScheduleRegime;
import lombok.*;

/**
 * 예상 스케줄 목록 조회 시 사용하는 검색 조건 DTO.
 * 값이 없는 조건은 조회 필터에서 제외한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-07
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleSearchCondition {
    private String contractNo;     // 계약번호
    private PaymentStage stage;          // 지급 단계
    private ScheduleRegime regime; // 적용 체계
    private SchedulePurpose purpose;        // 스케줄 용도
    private ScheduleHeaderStatus status;         // 스케줄 상태
}
