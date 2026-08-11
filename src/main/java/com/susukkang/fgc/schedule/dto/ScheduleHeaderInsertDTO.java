package com.susukkang.fgc.schedule.dto;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.schedule.code.ScheduleGenReason;
import com.susukkang.fgc.schedule.code.SchedulePurpose;
import com.susukkang.fgc.schedule.code.ScheduleRegime;
import com.susukkang.fgc.common.code.ScheduleHeaderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 예상 스케줄 헤더를 저장하기 위한 DTO.
 * INSERT 이후 생성된 스케줄 헤더 ID를 전달받는 용도로도 사용한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-09
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleHeaderInsertDTO {
    private Long scheduleHeaderId; // 생성된 스케줄 헤더 ID
    private Long contractId; // 계약 ID
    private PaymentStage paymentStage; // 지급 단계
    private Long policyVersionId; // 정책 버전 ID
    private Integer scheduleVersionNo; // 스케줄 버전
    private SchedulePurpose schedulePurpose; // 스케줄 용도
    private String scenarioCode; // 시나리오 코드
    private ScheduleRegime scheduleRegime; // 적용 체계
    private ScheduleHeaderStatus status; // 스케줄 상태
    private Boolean activeYn; // 현재 사용 여부
    private ScheduleGenReason generationReason; // 생성 사유
    private Long regeneratedFromId; // 재생성 원본 헤더 ID
}
