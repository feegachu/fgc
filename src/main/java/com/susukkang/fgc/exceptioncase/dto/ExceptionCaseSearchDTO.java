package com.susukkang.fgc.exceptioncase.dto;

import com.susukkang.fgc.common.code.ExceptionSeverity;
import com.susukkang.fgc.common.code.ExceptionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * IF-API-43 예외함 검색조건.
 * HTTP 쿼리 파라미터 이름과 필드명을 같게 두어 @ModelAttribute로 바로 바인딩한다.
 * status는 DB 상태 외에 화면 묶음값 OPEN(NEW+IN_REVIEW)을 허용하므로 String이다.
 * validationMonth는 exception_case.validation_month(검출 검증월) 필터다 —
 * ShellAdvice가 전 화면에 주입하는 셸 기준월 파라미터 month와는 무관하고,
 * 대시보드 KPI 카드 링크는 이 값을 보내지 않으므로 카드 건수↔목록 일치가 유지된다.
 */
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ExceptionCaseSearchDTO {
    private ExceptionType type;
    private String reasonCode;
    private ExceptionSeverity severity;
    private String status;
    private Long assignee;
    private boolean unassignedOnly;
    private String contractNo;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate validationMonth;
}
