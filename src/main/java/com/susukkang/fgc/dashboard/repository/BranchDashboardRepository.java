package com.susukkang.fgc.dashboard.repository;

import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.dashboard.dto.BranchDashboardResponse;
import com.susukkang.fgc.dashboard.dto.BranchDashboardResponse.BranchMetrics;
import com.susukkang.fgc.dashboard.dto.BranchDetailRow;
import com.susukkang.fgc.dashboard.dto.BranchMetric;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BranchDashboardRepository {
    private final NamedParameterJdbcTemplate jdbc;

    // The overview and drill-down share exactly the same branch mapping and source rows.
    private static final String SOURCES = HomeSummaryQueryRepository.ORG_BRANCH + """
            , agents AS (
                SELECT a.*, COALESCE(ob.branch_id, a.organization_id) AS branch_id
                  FROM fgc.agent a LEFT JOIN org_branch ob ON ob.origin_id = a.organization_id
                 WHERE a.appointment_date <= :asOf AND (a.termination_date IS NULL OR a.termination_date >= :asOf)
            ), contracts AS (
                SELECT c.*, COALESCE(ob.branch_id, c.organization_id) AS branch_id,
                       c.contract_date >= :month AND c.contract_date < :nextMonth AS is_new,
                       CASE WHEN :pastDate THEN COALESCE(on_date.new_status, after_date.previous_status, c.current_status)
                            ELSE c.current_status END AS as_of_status
                  FROM fgc.insurance_contract c LEFT JOIN org_branch ob ON ob.origin_id = c.organization_id
                  LEFT JOIN (SELECT DISTINCT ON (contract_id) contract_id, new_status FROM fgc.contract_status_event
                              WHERE effective_at < :asOfEnd ORDER BY contract_id, effective_at DESC, event_seq DESC) on_date
                    ON on_date.contract_id = c.contract_id
                  LEFT JOIN (SELECT DISTINCT ON (contract_id) contract_id, previous_status FROM fgc.contract_status_event
                              WHERE effective_at >= :asOfEnd ORDER BY contract_id, effective_at, event_seq) after_date
                    ON after_date.contract_id = c.contract_id
            ), payments AS (
                SELECT c.contract_id, c.contract_no, c.branch_id,
                       COALESCE(SUM(ta.attributed_amount) FILTER (WHERE ct.payment_stage = 'INSURER_TO_GA'), 0) AS ga_amount,
                       COALESCE(SUM(ta.attributed_amount) FILTER (WHERE ct.payment_stage = 'GA_TO_FC'), 0) AS agent_amount
                  FROM fgc.commission_transaction ct
                  JOIN fgc.transaction_attribution ta USING (commission_transaction_id)
                  JOIN contracts c ON c.contract_id = ta.contract_id
                 WHERE ct.settlement_month = :month AND ct.status = 'CONFIRMED'
                   AND ct.cashflow_type = 'PAYMENT' AND ta.attribution_scope = 'CONTRACT'
                 GROUP BY c.contract_id, c.contract_no, c.branch_id
            ), exceptions AS (
                SELECT ec.*, c.contract_no, COALESCE(c.branch_id, ob.branch_id, a.organization_id) AS branch_id
                  FROM fgc.exception_case ec LEFT JOIN contracts c ON c.contract_id = ec.contract_id
                  LEFT JOIN fgc.agent a ON a.agent_id = ec.agent_id
                  LEFT JOIN org_branch ob ON ob.origin_id = a.organization_id
                 WHERE ec.status IN ('NEW', 'IN_REVIEW')
            )
            """;

    private static final String SUMMARY = SOURCES + """
                , agent_totals AS (
                    SELECT branch_id, COUNT(*) AS registered,
                           COUNT(*) FILTER (WHERE agent_status = 'ACTIVE' AND active_yn) AS active FROM agents GROUP BY branch_id
                ), contract_totals AS (
                    SELECT branch_id, COUNT(*) FILTER (WHERE is_new) AS new_count,
                           COUNT(*) FILTER (WHERE contract_date <= :asOf AND as_of_status IN ('ACTIVE', 'UNPAID', 'REVIVED'))
                               AS valid_count FROM contracts GROUP BY branch_id
                ), payment_totals AS (
                    SELECT branch_id, SUM(ga_amount) AS ga_amount, SUM(agent_amount) AS agent_amount FROM payments GROUP BY branch_id
                ), exception_totals AS (
                    SELECT branch_id, COUNT(*) AS open_count FROM exceptions GROUP BY branch_id
                )
                SELECT o.organization_id, o.organization_name, COALESCE(a.registered, 0) AS registered,
                       COALESCE(a.active, 0) AS active, COALESCE(c.new_count, 0) AS new_count,
                       COALESCE(c.valid_count, 0) AS valid_count, COALESCE(p.ga_amount, 0) AS ga_amount,
                       COALESCE(p.agent_amount, 0) AS agent_amount, COALESCE(e.open_count, 0) AS open_count
                  FROM fgc.organization o
                  LEFT JOIN agent_totals a ON a.branch_id = o.organization_id
                  LEFT JOIN contract_totals c ON c.branch_id = o.organization_id
                  LEFT JOIN payment_totals p ON p.branch_id = o.organization_id
                  LEFT JOIN exception_totals e ON e.branch_id = o.organization_id
                 WHERE (o.organization_type = 'BRANCH' AND o.active_yn AND o.effective_from <= :asOf
                        AND (o.effective_to IS NULL OR o.effective_to >= :asOf))
                    OR a.registered > 0 OR c.new_count > 0 OR c.valid_count > 0 OR p.branch_id IS NOT NULL OR e.open_count > 0
                 ORDER BY o.organization_code
                """;

    // Metric is a bound value, never an SQL fragment supplied by a caller.
    private static final String DETAIL_ROWS = SOURCES + """
            , detail_rows AS (
                SELECT contract_id AS id, contract_no AS label, '' AS detail, '' AS status,
                       ga_amount AS value, agent_amount AS secondary_value
                  FROM payments WHERE :metric = 'COMMISSIONS' AND branch_id = :branch
                UNION ALL
                SELECT agent_id, agent_name, agent_code, agent_status, NULL::numeric, NULL::numeric
                  FROM agents WHERE :metric = 'AGENTS' AND branch_id = :branch
                UNION ALL
                SELECT contract_id, contract_no, CAST(contract_date AS text), as_of_status, NULL::numeric, NULL::numeric
                  FROM contracts WHERE :metric = 'CONTRACTS' AND branch_id = :branch AND (is_new OR (
                    contract_date <= :asOf AND as_of_status IN ('ACTIVE', 'UNPAID', 'REVIVED')
                ))
                UNION ALL
                SELECT exception_case_id, title, COALESCE(contract_no, ''), status, NULL::numeric, NULL::numeric
                  FROM exceptions WHERE :metric = 'EXCEPTIONS' AND branch_id = :branch
            )
            """;
    private static final String DETAIL_COUNT = DETAIL_ROWS + " SELECT COUNT(*) FROM detail_rows";
    private static final String DETAIL_PAGE = DETAIL_ROWS + " SELECT * FROM detail_rows ORDER BY id DESC LIMIT 10 OFFSET :offset";

    private MapSqlParameterSource parameters(LocalDate month) {
        LocalDate today = LocalDate.now(DateUtil.SEOUL_ZONE);
        LocalDate asOf = month.plusMonths(1).minusDays(1);
        if (asOf.isAfter(today)) asOf = today;
        return new MapSqlParameterSource("month", month).addValue("nextMonth", month.plusMonths(1))
                .addValue("asOf", asOf).addValue("pastDate", asOf.isBefore(today))
                .addValue("asOfEnd", asOf.plusDays(1).atStartOfDay(DateUtil.SEOUL_ZONE).toOffsetDateTime());
    }

    public BranchDashboardResponse summary(LocalDate month) {
        var params = parameters(month);
        var rows = jdbc.query(SUMMARY, params, (rs, index) -> new BranchMetrics(rs.getLong("organization_id"),
                rs.getString("organization_name"), rs.getLong("registered"), rs.getLong("active"),
                rs.getLong("new_count"), rs.getLong("valid_count"), rs.getBigDecimal("ga_amount"),
                rs.getBigDecimal("agent_amount"), rs.getLong("open_count")));
        return new BranchDashboardResponse(month, (LocalDate) params.getValue("asOf"), rows);
    }

    public PageResponse<BranchDetailRow> details(LocalDate month, long organizationId, BranchMetric metric, int requestedPage) {
        var params = parameters(month).addValue("branch", organizationId).addValue("metric", metric.name());
        long total = jdbc.queryForObject(DETAIL_COUNT, params, Long.class);
        int totalPages = (int) ((total + 9) / 10);
        int page = Math.clamp(requestedPage, 1, Math.max(1, totalPages));
        params.addValue("offset", (page - 1) * 10);
        var rows = jdbc.query(DETAIL_PAGE, params,
                (rs, index) -> new BranchDetailRow(rs.getLong("id"), rs.getString("label"), rs.getString("detail"),
                        rs.getString("status"), rs.getBigDecimal("value"), rs.getBigDecimal("secondary_value")));
        return new PageResponse<>(rows, page, 10, total, totalPages, "id,desc");
    }
}
