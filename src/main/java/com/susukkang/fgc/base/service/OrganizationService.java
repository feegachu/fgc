package com.susukkang.fgc.base.service;

import com.susukkang.fgc.base.dto.OrganizationResponse;
import com.susukkang.fgc.base.dto.OrganizationSearchCriteria;
import com.susukkang.fgc.common.web.PageResponse;

public interface OrganizationService {

    PageResponse<OrganizationResponse> search(
            OrganizationSearchCriteria criteria,
            int page,
            int size
    );
}
