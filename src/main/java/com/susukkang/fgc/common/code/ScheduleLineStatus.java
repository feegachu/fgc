package com.susukkang.fgc.common.code;

/**
 * 설명 : 예상 스케줄 상세의 status를 enum으로 표현
 * status 상태에 따라 예정,확정,일치,조정,중단,취소,재시작이 존재
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-10
 */
public enum ScheduleLineStatus {
    PLANNED,  //예정 계약이 생성되고 막 생성 된 상태
    CONFIRMED,//확정 정산 담당자가 확정을 누를 경우
    MATCHED,  //대사일치 실제 수수료 지급과 명세가 같은 경우
    ADJUSTED, // 조정: 실제 수수료 지급과 명세가 다른 경우
    HOLD,     //보류 계약이 미납된 경우
    CANCELLED,//취소 계약이 해지된 경우
    RESTARTED //재개 계약이 부활한 경우
}
