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
import java.util.List;

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
    // 2026-08-19 yslee - FUN-044 확정 체크리스트의 실행·복수유형 검색조건 유지
    // 기존 코드: develop 예외함 개편에서 검증월·상세원인은 지원하지만 실행 ID·복수유형은 제외
    // 문제: 확정 실패 바로가기에서 다른 실행의 예외가 섞이고 정책 누락·중복을 한 번에 조회할 수 없음
    // 개선: 공통 서비스 검색 DTO에 선택 실행 ID와 복수 예외유형 조건을 함께 제공
    private Long validationRunId;
    private List<ExceptionType> types;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate validationMonth;
}
