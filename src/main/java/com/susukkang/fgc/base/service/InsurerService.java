package com.susukkang.fgc.base.service;

import com.susukkang.fgc.base.dto.InsurerResponse;
import com.susukkang.fgc.common.web.PageResponse;

public interface InsurerService {

    PageResponse<InsurerResponse> search(String keyword, int page, int size);
}
