package com.susukkang.fgc.contract.dto;

import com.susukkang.fgc.schedule.dto.ScheduleHeaderResponse;
import com.susukkang.fgc.schedule.dto.ScheduleLineResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
/**
 * 계약 탭 상세보기 - 스케줄 확인
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-11
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractScheduleResponse {
    private List<ScheduleHeaderResponse> headers; //헤더 스케줄
    private List<ScheduleLineResponse> lines;    //상세 스케줄
}
