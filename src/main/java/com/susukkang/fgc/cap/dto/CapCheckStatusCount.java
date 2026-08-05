package com.susukkang.fgc.cap.dto;

import lombok.Getter;
import lombok.Setter;

/** CAP-W01 요약 카드 4장 집계용 — result_status 별 건수 1행. */
@Getter
@Setter
public class CapCheckStatusCount {
    private String resultStatus;
    private long count;
}
