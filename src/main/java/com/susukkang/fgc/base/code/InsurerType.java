package com.susukkang.fgc.base.code;

/**
 * 보험회사(원수사) 유형. fgc.insurer.insurer_type CHECK 제약조건의 값과 일치해야 한다.
 */
public enum InsurerType {
    LIFE,
    NON_LIFE;

    public String label() {
        return switch (this) {
            case LIFE -> "생명보험";
            case NON_LIFE -> "손해보험";
        };
    }
}
