package com.susukkang.fgc.contract.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.security.AgentDataScopeService;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.contract.dto.ContractStatusEffectResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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
    /** FUN-085: AGENT는 같은 계약이어도 본인 회차와 본인 지급 건의 영향만 본다. 다른 수혜자 회차, 원수사→GA 지급, 남의 지급 건은 내주지 않는다. */
    private static final String OWN_EFFECT = """
            AND ((e.entity_type = 'SCHEDULE_LINE' AND EXISTS (SELECT 1 FROM fgc.schedule_line sl
                    WHERE sl.schedule_line_id = e.entity_id AND sl.beneficiary_agent_id = :agentId))
                OR (e.entity_type = 'PAYMENT' AND EXISTS (SELECT 1 FROM fgc.commission_transaction ct
                    WHERE ct.commission_transaction_id = e.entity_id AND %s)))
            """.formatted(AgentDataScopeService.OWN_PAYMENT);
    /** 저장된 지급 건 금액은 헤더 총액이다. 남의 몫이 섞인 건은 AGENT에게 본인 귀속 합계로 보인다. */
    private static final String AGENT_VISIBLE_AMOUNT = """
            CASE WHEN e.entity_type = 'PAYMENT' THEN COALESCE((%s), e.amount) ELSE e.amount END
            """.formatted(AgentDataScopeService.ownAmountIfMixed("e.entity_id"));

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
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
        Long scopeAgentId = agentScope.currentAgentId();
        String ownEffect = scopeAgentId == null ? "" : OWN_EFFECT;
        var params = new MapSqlParameterSource("eventId", eventId).addValue("agentId", scopeAgentId)
                .addValue("size", size).addValue("offset", (long) (page - 1) * size);
        long total = namedJdbc.queryForObject("SELECT count(*) FROM fgc.contract_status_event_effect e"
                + " WHERE e.contract_status_event_id = :eventId " + ownEffect, params, Long.class);
        List<ContractStatusEffectResponse> rows = namedJdbc.query("""
                SELECT e.entity_type, e.entity_id, e.processing_job, e.previous_state, e.next_state,
                       __AMOUNT__ AS amount, e.policy_version_id, e.reason_code, e.reason
                  FROM fgc.contract_status_event_effect e
                 WHERE e.contract_status_event_id = :eventId __OWN_EFFECT__
                 ORDER BY e.entity_type, e.entity_id, e.processing_job
                 LIMIT :size OFFSET :offset
                """.replace("__AMOUNT__", scopeAgentId == null ? "e.amount" : AGENT_VISIBLE_AMOUNT)
                .replace("__OWN_EFFECT__", ownEffect), params, (rs, row) -> new ContractStatusEffectResponse(rs.getString("entity_type"), rs.getLong("entity_id"),
                rs.getString("processing_job"), rs.getString("previous_state"), rs.getString("next_state"),
                rs.getBigDecimal("amount"), (Long) rs.getObject("policy_version_id"), rs.getString("reason_code"),
                rs.getString("reason")));
        return new PageResponse<>(rows, page, size, total, (int) Math.ceil(total / (double) size), "entityType,entityId");
    }

    private static FgcBusinessException invalid(String field) {
        return new FgcBusinessException(FgcErrorCode.COMMON_002, field, Map.of("field", field), null);
    }
}
