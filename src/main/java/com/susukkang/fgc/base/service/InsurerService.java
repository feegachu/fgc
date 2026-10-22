package com.susukkang.fgc.base.service;

import com.susukkang.fgc.base.dto.InsurerResponse;
import com.susukkang.fgc.common.web.PageResponse;

public interface InsurerService {

    /** 정렬 없이(보험회사 코드 오름차순) 조회한다. */
    default PageResponse<InsurerResponse> search(String keyword, int page, int size) {
        return search(keyword, null, page, size);
    }

    /** sort 는 {@code key,asc|desc} 형식이며(#604) 비어 있으면 보험회사 코드 오름차순이다. */
    PageResponse<InsurerResponse> search(String keyword, String sort, int page, int size);
}
