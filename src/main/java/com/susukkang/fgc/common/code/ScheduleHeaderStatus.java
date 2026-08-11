package com.susukkang.fgc.common.code;

/**
 * 설명 : 스케줄 헤더의 상태 enum
 *  계획,조정,중단,취소 등
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-10
 */
public enum ScheduleHeaderStatus {
    PLANNED,  //예정 계약이 생성되고 막 생성 된 상태
    CONFIRMED,//확정 정산 담당자가 확정을 누를 경우
    MATCHED,  //대사일치 실제 수수료 지급과 명세가 같은 경우
    ADJUSTED, //조정완료 실제 수수료 지급과 명세가 다른 경우
    HOLD,     //보류 계약이 미납된 경우
    CANCELLED,//취소 계약이 해지된 경우
    RESTARTED //재개 계약이 부활한 경우
}
