package com.susukkang.fgc.common.code;

/**
 * cap_check.result_status 의 자바측 표현.
 * DB CHECK (result_status IN ('NORMAL','WARNING','VIOLATION','REVIEW_REQUIRED')) 와 반드시 일치해야 한다.
 */
public enum CapResultStatus {
    NORMAL,
    WARNING,
    VIOLATION,
    REVIEW_REQUIRED
}
