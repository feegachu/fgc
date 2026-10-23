package com.susukkang.fgc.validation.repository;

import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.common.web.ListSort;
import com.susukkang.fgc.validation.dto.ValidationRunListRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;

/** VRUN-W01 목록의 컬럼별 정렬 조회(#604). 검색된 전체 데이터를 정렬한 뒤 현재 페이지를 자른다. */
public interface ValidationRunSearch {

    /** month/status 는 null 이면 그 조건을 걸지 않는다. Pageable 은 범위에만 쓰고 정렬은 sort 로 정한다. */
    Page<ValidationRunListRow> searchSorted(LocalDate month, ValidationRunStatus status, ListSort sort, Pageable pageable);
}
