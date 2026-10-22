package com.susukkang.fgc.base.repository;

import com.susukkang.fgc.base.dto.ProductRow;
import com.susukkang.fgc.common.web.ListSort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;

/** 기준정보 상품 탭의 컬럼별 정렬 조회(#604). 검색된 전체 데이터를 정렬한 뒤 현재 페이지를 자른다. */
public interface ProductSearch {

    /** sort 가 null 이면 기존 기본 정렬(상품코드·판매버전·채널·판매버전 ID)이다. Pageable 은 범위에만 쓴다. */
    Page<ProductRow> searchSorted(long insurerId, LocalDate asOf, boolean includeExpired, boolean includeHistorical,
                                  ListSort sort, Pageable pageable);
}
