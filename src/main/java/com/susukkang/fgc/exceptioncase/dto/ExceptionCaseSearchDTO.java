package com.susukkang.fgc.exceptioncase.dto;

import com.susukkang.fgc.common.code.ExceptionSeverity;
import com.susukkang.fgc.common.code.ExceptionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * IF-API-43 예외함 검색조건.
 * HTTP 쿼리 파라미터 이름과 필드명을 같게 두어 @ModelAttribute로 바로 바인딩한다.
 * status는 DB 상태 외에 화면 묶음값 OPEN(NEW+IN_REVIEW)을 허용하므로 String이다.
 */
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ExceptionCaseSearchDTO {
    private ExceptionType type;
    private ExceptionSeverity severity;
    private String status;
    private Long assignee;
    private boolean unassignedOnly;
    private String contractNo;

    // 2026-08-19 yslee - 확정 체크리스트의 실행·복수유형 바로가기 조회 조건 추가
    // 기존 코드: IF-API-43 단일 유형과 화면 기본 검색조건만 지원
    // 문제: FUN-044 체크리스트 링크의 실행 범위와 정책 누락·중복 복수유형을 정확히 조회할 수 없음
    // 개선: 선택 실행 ID와 복수 예외유형을 기존 조회 API의 추가 선택조건으로 지원
    private Long validationRunId;
    private List<ExceptionType> types;
}
