package com.susukkang.fgc.schedule.code;

public enum SchedulePurpose {
    OPERATIONAL("실제 지급용"),
    COMPARISON("비교용"),
    SIMULATION("모의 계산용");

    private final String label;

    SchedulePurpose(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static String labelOf(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return valueOf(code).label();
        } catch (IllegalArgumentException exception) {
            return code;
        }
    }
}
