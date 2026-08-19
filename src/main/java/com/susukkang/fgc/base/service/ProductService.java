package com.susukkang.fgc.base.service;

import com.susukkang.fgc.base.dto.ProductResponse;
import com.susukkang.fgc.base.dto.ProductSearchCriteria;
import com.susukkang.fgc.common.web.PageResponse;

public interface ProductService {

    PageResponse<ProductResponse> search(
            ProductSearchCriteria criteria,
            int page,
            int size
    );
}
