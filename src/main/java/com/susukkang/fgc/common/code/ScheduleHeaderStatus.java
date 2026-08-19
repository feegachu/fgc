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
    PLANNED("예정"),
    CONFIRMED("확정"),
    MATCHED("대사일치"),
    ADJUSTED("조정"),
    HOLD("보류"),
    CANCELLED("취소"),
    RESTARTED("재개");

    private final String label;

    ScheduleHeaderStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
