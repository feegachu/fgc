package com.susukkang.fgc.exceptioncase.dto;

import com.susukkang.fgc.common.code.ExceptionSeverity;
import com.susukkang.fgc.common.code.ExceptionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
    private String reasonCode;
    private ExceptionSeverity severity;
    private String status;
    private Long assignee;
    private boolean unassignedOnly;
    private String contractNo;
}
