package com.susukkang.fgc.report.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 명세 조회의 기준월·날짜 기준·알려둘 경고. 유형마다 기준월을 보는 날짜가 달라 화면에 함께 보여준다. */
public record StatementBasis(
        String month,
        Range range,
        Map<String, String> dateBasis,
        List<String> warnings
) {
    public record Range(LocalDate from, LocalDate to) {
    }
}
