package com.susukkang.fgc.schedule.code;

public enum ScheduleRegime {
    CURRENT("현행"),
    FOUR_YEAR_2027("4년 분급(2027)"),
    SEVEN_YEAR_2029("7년 분급(2029)"),
    TM_SPECIAL("TM 특례");

    private final String label;

    ScheduleRegime(String label) {
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
