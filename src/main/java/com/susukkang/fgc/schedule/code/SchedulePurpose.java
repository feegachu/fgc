package com.susukkang.fgc.schedule.code;

public enum SchedulePurpose {
    OPERATIONAL("운영"),
    COMPARISON("비교"),
    SIMULATION("시뮬레이션");

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
