package com.susukkang.fgc.dashboard.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 설명 : IF-API-68 관리자 홈 현황의 원천 집계. 설계사별 N+1 을 만들지 않도록 설계사 행과 지점별 유효 계약을 각각 한 번에 읽는다.
 * 재귀 조직 탐색과 DISTINCT ON 때문에 네이티브 SQL 로 둔다.
 *
 * @author yslee
 * @since 2026-11-04
 * @version 1.0
 */
@Repository
@RequiredArgsConstructor
public class HomeSummaryQueryRepository {

    /** 소속 조직에서 가장 가까운 지점. 지점 위 조직(본부 등)에 바로 소속되면 행이 없고, 호출부가 그 조직 자체로 묶는다. */
    private static final String ORG_BRANCH = """
            WITH RECURSIVE org_walk AS (
                SELECT o.organization_id AS origin_id, o.organization_id, o.parent_id, o.organization_type, 0 AS depth
                  FROM fgc.organization o
                UNION ALL
                SELECT w.origin_id, p.organization_id, p.parent_id, p.organization_type, w.depth + 1
                  FROM org_walk w
                  JOIN fgc.organization p ON p.organization_id = w.parent_id
                 WHERE w.organization_type <> 'BRANCH'
            ),
            org_branch AS (
                SELECT DISTINCT ON (origin_id) origin_id, organization_id AS branch_id
                  FROM org_walk
                 WHERE organization_type = 'BRANCH'
                 ORDER BY origin_id, depth
            )
            """;

    private static final String AGENT_ROWS = ORG_BRANCH + """
            ,
            contract_count AS (
                SELECT c.agent_id, COUNT(*) AS contract_count
                  FROM fgc.insurance_contract c
                 WHERE c.contract_date <= :asOf
                 GROUP BY c.agent_id
            ),
            open_exception AS (
                SELECT COALESCE(ec.agent_id, c.agent_id) AS agent_id, COUNT(*) AS open_count
                  FROM fgc.exception_case ec
                  LEFT JOIN fgc.insurance_contract c ON c.contract_id = ec.contract_id
                 WHERE ec.status IN (:openStatuses) AND ec.created_at < :asOfEnd
                 GROUP BY COALESCE(ec.agent_id, c.agent_id)
            ),
            latest_cap AS (
                -- vw_latest_cap_check 와 같은 최신 판정 규칙에 기준일 상한만 더한다. 주의가 한 번도 없던 계약은 미리 빼서 정렬량을 줄인다.
                SELECT DISTINCT ON (cc.contract_id, cc.payment_stage) cc.contract_id, cc.result_status
                  FROM fgc.cap_check cc
                 WHERE cc.as_of_date <= :asOf
                   AND cc.contract_id IN (SELECT w.contract_id FROM fgc.cap_check w
                                           WHERE w.result_status = 'WARNING' AND w.as_of_date <= :asOf)
                   AND ((cc.candidate_transaction_id IS NULL
                         AND NOT jsonb_exists(cc.calculation_snapshot, 'deletedCandidateTransactionId'))
                        OR EXISTS (SELECT 1 FROM fgc.commission_transaction candidate
                                    WHERE candidate.commission_transaction_id = cc.candidate_transaction_id
                                      AND candidate.status = 'CONFIRMED'))
                 ORDER BY cc.contract_id, cc.payment_stage, cc.checked_at DESC, cc.cap_check_id DESC
            ),
            near_limit AS (
                SELECT c.agent_id, COUNT(DISTINCT lc.contract_id) AS near_limit_count
                  FROM latest_cap lc
                  JOIN fgc.insurance_contract c ON c.contract_id = lc.contract_id
                 WHERE lc.result_status = 'WARNING'
                 GROUP BY c.agent_id
            ),
            recent_change AS (
                SELECT c.agent_id, COUNT(DISTINCT e.contract_id) AS recent_change_count
                  FROM fgc.contract_status_event e
                  JOIN fgc.insurance_contract c ON c.contract_id = e.contract_id
                 WHERE e.new_status IN (:eventStatuses) AND e.effective_at >= :recentFrom AND e.effective_at < :asOfEnd
                 GROUP BY c.agent_id
            )
            SELECT a.agent_id, a.agent_code, a.agent_name, a.agent_status, a.active_yn, a.termination_date,
                   branch.organization_id AS branch_id, branch.organization_code AS branch_code,
                   branch.organization_name AS branch_name,
                   COALESCE(cn.contract_count, 0) AS contract_count,
                   COALESCE(oe.open_count, 0) AS open_count,
                   COALESCE(nl.near_limit_count, 0) AS near_limit_count,
                   COALESCE(rc.recent_change_count, 0) AS recent_change_count
              FROM fgc.agent a
              LEFT JOIN org_branch ob ON ob.origin_id = a.organization_id
              JOIN fgc.organization branch ON branch.organization_id = COALESCE(ob.branch_id, a.organization_id)
              LEFT JOIN contract_count cn ON cn.agent_id = a.agent_id
              LEFT JOIN open_exception oe ON oe.agent_id = a.agent_id
              LEFT JOIN near_limit nl ON nl.agent_id = a.agent_id
              LEFT JOIN recent_change rc ON rc.agent_id = a.agent_id
             WHERE a.appointment_date <= :asOf
            """;

    /** 유효 계약은 계약에 저장된 조직으로 센다. 해촉자의 계약도 지점에 남아 소계가 전체 유효 계약과 맞는다. */
    private static final String BRANCH_ROWS = ORG_BRANCH + """
            ,
            valid_contract AS (
                SELECT COALESCE(ob.branch_id, c.organization_id) AS branch_id, COUNT(*) AS contract_count
                  FROM fgc.insurance_contract c
                  LEFT JOIN org_branch ob ON ob.origin_id = c.organization_id
                  LEFT JOIN (SELECT DISTINCT ON (e.contract_id) e.contract_id, e.new_status
                               FROM fgc.contract_status_event e
                              WHERE e.effective_at < :asOfEnd
                              ORDER BY e.contract_id, e.effective_at DESC, e.event_seq DESC) on_date
                         ON on_date.contract_id = c.contract_id
                  LEFT JOIN (SELECT DISTINCT ON (e.contract_id) e.contract_id, e.previous_status
                               FROM fgc.contract_status_event e
                              WHERE e.effective_at >= :asOfEnd
                              ORDER BY e.contract_id, e.effective_at, e.event_seq) after_date
                         ON after_date.contract_id = c.contract_id
                 WHERE c.contract_date <= :asOf
                   AND CASE WHEN :pastDate
                            THEN COALESCE(on_date.new_status, after_date.previous_status, c.current_status)
                            ELSE c.current_status END IN (:validStatuses)
                 GROUP BY COALESCE(ob.branch_id, c.organization_id)
            )
            SELECT o.organization_id, o.organization_code, o.organization_name, COALESCE(vc.contract_count, 0) AS contract_count
              FROM fgc.organization o
              LEFT JOIN valid_contract vc ON vc.branch_id = o.organization_id
             WHERE vc.branch_id IS NOT NULL
                OR (o.organization_type = 'BRANCH' AND o.active_yn AND o.effective_from <= :asOf
                    AND (o.effective_to IS NULL OR o.effective_to >= :asOf))
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public record AgentRow(long agentId, String code, String name, String status, boolean activeYn, LocalDate terminationDate,
                           long branchId, String branchCode, String branchName,
                           long contractCount, long openExceptionCount, long nearLimitCount, long recentChangeCount) {
    }

    public record BranchRow(long organizationId, String code, String name, long activeContractCount) {
    }

    /** 기준일까지 위촉된 설계사 전원(해촉자 포함)과 사유별 건수. 600명이어도 한 번의 집계로 읽는다. */
    public List<AgentRow> agentRows(LocalDate asOf, OffsetDateTime asOfEnd, OffsetDateTime recentFrom,
                                    List<String> openStatuses, List<String> eventStatuses) {
        MapSqlParameterSource params = new MapSqlParameterSource("asOf", asOf).addValue("asOfEnd", asOfEnd)
                .addValue("recentFrom", recentFrom).addValue("openStatuses", openStatuses)
                .addValue("eventStatuses", eventStatuses);
        return jdbc.query(AGENT_ROWS, params, (rs, row) -> new AgentRow(
                rs.getLong("agent_id"), rs.getString("agent_code"), rs.getString("agent_name"),
                rs.getString("agent_status"), rs.getBoolean("active_yn"), rs.getObject("termination_date", LocalDate.class),
                rs.getLong("branch_id"), rs.getString("branch_code"), rs.getString("branch_name"),
                rs.getLong("contract_count"), rs.getLong("open_count"), rs.getLong("near_limit_count"),
                rs.getLong("recent_change_count")));
    }

    /**
     * 기준일에 열려 있는 지점과 유효 계약이 있는 조직. 지난 기준일의 계약 상태는 그날까지의 마지막 사건, 없으면 그 뒤 첫 사건의 이전 상태로 보고,
     * 사건이 없는 계약과 오늘 이후 기준일은 현재 상태를 쓴다.
     */
    public List<BranchRow> branchRows(LocalDate asOf, OffsetDateTime asOfEnd, boolean pastDate, List<String> validStatuses) {
        MapSqlParameterSource params = new MapSqlParameterSource("asOf", asOf).addValue("asOfEnd", asOfEnd)
                .addValue("pastDate", pastDate).addValue("validStatuses", validStatuses);
        return jdbc.query(BRANCH_ROWS, params, (rs, row) -> new BranchRow(
                rs.getLong("organization_id"), rs.getString("organization_code"), rs.getString("organization_name"),
                rs.getLong("contract_count")));
    }
}
