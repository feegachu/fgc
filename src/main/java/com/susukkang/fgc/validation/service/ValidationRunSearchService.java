package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.validation.dto.ValidationRunListRow;
import com.susukkang.fgc.validation.dto.ValidationRunSearchCriteria;

import java.time.LocalDate;

/**
 * FGC-FUN-041 월 통합검증 실행 목록 조회
 */
public interface ValidationRunSearchService {

    /**
     * 검증월(criteria.month())·상태(criteria.status())로 거른 validation_run 목록을 페이징해서 돌려줌
     * page/size 계약 위반(1-base, size 1~100)은 FgcErrorCode.COMMON_002로 던짐
     */
    PageResponse<ValidationRunListRow> search(ValidationRunSearchCriteria criteria, int page, int size);

    /**
     * validationMonth에 활성(CREATED·RUNNING) MONTHLY 실행이 있는지 —
     * VRUN-W01 생성 버튼 비활성 판정용(화면정의서 :1379)
     */
    boolean existsActiveMonthlyRun(LocalDate validationMonth);
}
