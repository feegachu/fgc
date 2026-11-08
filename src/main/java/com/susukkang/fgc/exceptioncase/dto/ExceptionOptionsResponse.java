package com.susukkang.fgc.exceptioncase.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * IF-API-43A 예외함 필터 선택지 응답.
 * 화면이 select 를 그리는 데 필요한 값(코드·한글 라벨)을 한 번에 내려준다.
 * 담당자 필터는 배정 이력이 있는 사용자만, 배정 선택지는 활성 예외 처리 담당자를 담는다.
 */
public record ExceptionOptionsResponse(
        List<Option> types,
        List<Option> reasons,
        List<Option> severities,
        List<ExceptionAssigneeRow> assignees,
        List<LocalDate> validationMonths,
        List<ExceptionAssigneeRow> assignableUsers
) {
    public ExceptionOptionsResponse {
        types = List.copyOf(types);
        reasons = List.copyOf(reasons);
        severities = List.copyOf(severities);
        assignees = List.copyOf(assignees);
        validationMonths = List.copyOf(validationMonths);
        assignableUsers = List.copyOf(assignableUsers);
    }

    /** 영문 코드와 한글 라벨 쌍(SIR-008). */
    public record Option(String code, String label) {
    }
}
