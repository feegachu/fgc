package com.susukkang.fgc.cap.dto;

import com.susukkang.fgc.common.web.PageResponse;

/** IF-API-30 응답 본체 — summary 4장 + 페이징된 목록. */
public record CapCheckSearchResult(
        CapCheckSummary summary,
        java.util.List<CapStageSummaryRow> stageSummary,
        java.util.List<CapAgentSummaryRow> agentSummary,
        PageResponse<CapCheckListRow> page
) {
}
