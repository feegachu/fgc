package com.susukkang.fgc.base.service;

import com.susukkang.fgc.base.dto.AgentResponse;
import com.susukkang.fgc.base.dto.AgentSearchCriteria;
import com.susukkang.fgc.common.web.PageResponse;

public interface AgentService {

    PageResponse<AgentResponse> search(AgentSearchCriteria criteria, int page, int size);
}
