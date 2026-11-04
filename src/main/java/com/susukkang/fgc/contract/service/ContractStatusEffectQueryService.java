package com.susukkang.fgc.contract.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.security.AgentDataScopeService;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.contract.dto.ContractStatusEffectResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 설명 : IF-API-89 상태 사건의 영향 조회. 사건이 그 계약 소속인지 먼저 확인하고(남의 사건 ID로 조회 불가) 같은 기준으로 목록과 건수를 센다.
 *
 * @version 1.0
 * @since 2026-11-03
 */
@Service
@RequiredArgsConstructor
public class ContractStatusEffectQueryService {

    private static final int MAX_SIZE = 100;
    private final JdbcTemplate jdbc;
    private final AgentDataScopeService agentScope;

    @Transactional(readOnly = true)
    public PageResponse<ContractStatusEffectResponse> effects(Long contractId, Long eventId, int page, int size) {
        if (page < 1) {
            throw invalid("page");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw invalid("size");
        }
        agentScope.requireContract(contractId);
        Integer owned = jdbc.queryForObject(
                "SELECT count(*) FROM fgc.contract_status_event WHERE contract_status_event_id = ? AND contract_id = ?",
                Integer.class, eventId, contractId);
        if (owned == null || owned == 0) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_004, "eventId", Map.of("eventId", eventId), null);
        }
        long total = jdbc.queryForObject(
                "SELECT count(*) FROM fgc.contract_status_event_effect WHERE contract_status_event_id = ?", Long.class, eventId);
        List<ContractStatusEffectResponse> rows = jdbc.query("""
                SELECT entity_type, entity_id, processing_job, previous_state, next_state, amount, policy_version_id,
                       reason_code, reason
                  FROM fgc.contract_status_event_effect
                 WHERE contract_status_event_id = ?
                 ORDER BY entity_type, entity_id, processing_job
                 LIMIT ? OFFSET ?
                """, (rs, row) -> new ContractStatusEffectResponse(rs.getString("entity_type"), rs.getLong("entity_id"),
                rs.getString("processing_job"), rs.getString("previous_state"), rs.getString("next_state"),
                rs.getBigDecimal("amount"), (Long) rs.getObject("policy_version_id"), rs.getString("reason_code"),
                rs.getString("reason")), eventId, size, (long) (page - 1) * size);
        return new PageResponse<>(rows, page, size, total, (int) Math.ceil(total / (double) size), "entityType,entityId");
    }

    private static FgcBusinessException invalid(String field) {
        return new FgcBusinessException(FgcErrorCode.COMMON_002, field, Map.of("field", field), null);
    }
}
