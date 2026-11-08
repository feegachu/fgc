package com.susukkang.fgc.journal.repository;

import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.journal.domain.JournalAccountCode;
import com.susukkang.fgc.journal.dto.JournalExplorer;
import com.susukkang.fgc.journal.dto.JournalExplorer.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 계약 연결은 헤더와 상세행을 함께 읽는다. 계정 필터가 있어도 분개의 모든 차변·대변 행을 유지한다. */
@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JournalExplorerRepository {
    private static final String DEBIT_TOTAL = "debit_total";
    private static final String CREDIT_TOTAL = "credit_total";
    private final NamedParameterJdbcTemplate jdbc;
    private static final String SCOPE = """
        WITH links AS (
          SELECT journal_header_id, contract_id FROM fgc.journal_header WHERE contract_id IS NOT NULL
          UNION SELECT journal_header_id, contract_id FROM fgc.journal_line WHERE contract_id IS NOT NULL
          UNION SELECT h.journal_header_id, NULL::bigint FROM fgc.journal_header h
            WHERE h.contract_id IS NULL AND NOT EXISTS (
              SELECT 1 FROM fgc.journal_line l WHERE l.journal_header_id = h.journal_header_id AND l.contract_id IS NOT NULL)
        ), scoped AS (
          SELECT h.*, x.contract_id AS linked_contract_id, c.contract_no, a.agent_name, i.insurer_name,
            (SELECT COALESCE(sum(l.debit_amount),0) FROM fgc.journal_line l WHERE l.journal_header_id=h.journal_header_id) AS debit_total,
            (SELECT COALESCE(sum(l.credit_amount),0) FROM fgc.journal_line l WHERE l.journal_header_id=h.journal_header_id) AS credit_total
          FROM fgc.journal_header h JOIN links x ON x.journal_header_id=h.journal_header_id
          LEFT JOIN fgc.insurance_contract c ON c.contract_id=x.contract_id
          LEFT JOIN fgc.agent a ON a.agent_id=c.agent_id
          LEFT JOIN fgc.insurer i ON i.insurer_id=c.insurer_id
          WHERE (CAST(:from AS date) IS NULL OR h.journal_date >= :from)
            AND (CAST(:to AS date) IS NULL OR h.journal_date <= :to)
            AND (CAST(:type AS varchar) IS NULL OR h.journal_type = :type)
            AND (CAST(:status AS varchar) IS NULL OR h.status = :status)
            AND (CAST(:contractNo AS varchar) IS NULL OR c.contract_no ILIKE :contractNo ESCAPE '\\')
            AND (CAST(:account AS varchar) IS NULL OR EXISTS (
              SELECT 1 FROM fgc.journal_line l JOIN fgc.journal_account a ON a.journal_account_id=l.journal_account_id
              WHERE l.journal_header_id=h.journal_header_id AND a.account_code=:account))
        )
        """;

    private static final String SELECTED_SCOPE = SCOPE + """
            , entries AS (SELECT DISTINCT journal_header_id,journal_no,journal_date,journal_type,status,
                validation_run_id,description,debit_total,credit_total FROM scoped
              WHERE (CAST(:selected AS bigint) IS NULL OR COALESCE(linked_contract_id,0)=:selected)
                AND (CAST(:journalId AS bigint) IS NULL OR journal_header_id=:journalId))
            """;

    private static Long selectedContract(boolean all, Long contractId, List<Contract> contracts) {
        if (all) return null;
        if (contractId != null) return contractId;
        if (contracts.isEmpty()) return null;
        return contracts.getFirst().contractId();
    }

    public JournalExplorer explore(Filter filter, Long contractId, boolean all, Long journalId,
                                   int contractPage, int page, int size) {
        if (contractPage < 1 || page < 1 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Invalid explorer pagination");
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("from", filter.from()).addValue("to", filter.to()).addValue("type", filter.type())
                .addValue("status", filter.status()).addValue("account", blank(filter.account()))
                .addValue("contractNo", blank(filter.contractNo()) == null ? null : "%" + escape(filter.contractNo().trim()) + "%")
                .addValue("limit", size).addValue("offset", ((long) contractPage - 1L) * size);
        long contractCount = count(SCOPE + "SELECT count(DISTINCT COALESCE(linked_contract_id,0)) FROM scoped", parameters);
        List<Contract> contracts = jdbc.query(SCOPE + """
            SELECT COALESCE(linked_contract_id,0) AS contract_id, COALESCE(contract_no,'공통·미연결') AS contract_no,
                   count(*) AS journal_count, sum(debit_total) AS debit_total, sum(credit_total) AS credit_total,
                   max(agent_name) AS agent_name, max(insurer_name) AS insurer_name
            FROM scoped GROUP BY linked_contract_id,contract_no ORDER BY contract_no NULLS LAST,linked_contract_id
            LIMIT :limit OFFSET :offset
            """, parameters, (rs, row) -> new Contract(rs.getLong("contract_id"), rs.getString("contract_no"),
                rs.getLong("journal_count"), rs.getBigDecimal(DEBIT_TOTAL), rs.getBigDecimal(CREDIT_TOTAL),rs.getString("agent_name"),rs.getString("insurer_name")));
        Long selected = selectedContract(all, contractId, contracts);
        parameters.addValue("selected", selected).addValue("journalId", journalId).addValue("offset", ((long) page - 1L) * size);
        long journalCount = count(SELECTED_SCOPE + "SELECT count(*) FROM entries", parameters);
        Totals totals = jdbc.queryForObject(SELECTED_SCOPE + """
            SELECT count(*) AS journal_count, COALESCE(sum(debit_total),0) AS debit_total, COALESCE(sum(credit_total),0) AS credit_total,
              (SELECT count(*) FROM fgc.journal_line l JOIN entries e ON e.journal_header_id=l.journal_header_id) AS line_count FROM entries
            """,parameters,(rs,row)->new Totals(rs.getLong("journal_count"),rs.getLong("line_count"),rs.getBigDecimal(DEBIT_TOTAL),rs.getBigDecimal(CREDIT_TOTAL)));
        List<Entry> headers = jdbc.query(SELECTED_SCOPE + "SELECT * FROM entries ORDER BY journal_date DESC,journal_header_id DESC LIMIT :limit OFFSET :offset",
                parameters, (rs, row) -> entry(rs, List.of()));
        Map<Long, List<Line>> lines = new HashMap<>();
        if (!headers.isEmpty()) {
            parameters.addValue("ids", headers.stream().map(Entry::journalHeaderId).toList());
            jdbc.query("""
                SELECT l.*,a.account_code,a.account_name FROM fgc.journal_line l
                JOIN fgc.journal_account a ON a.journal_account_id=l.journal_account_id
                WHERE l.journal_header_id IN (:ids) ORDER BY l.journal_header_id,l.line_no
                """, parameters, rs -> {
                lines.computeIfAbsent(rs.getLong("journal_header_id"), ignored -> new ArrayList<>()).add(new Line(
                        rs.getInt("line_no"),rs.getString("account_code"),
                        JournalAccountCode.displayName(rs.getString("account_code"),rs.getString("account_name")),
                        rs.getBigDecimal("debit_amount"),rs.getBigDecimal("credit_amount"),rs.getString("memo")));
            });
        }
        List<Entry> entries = headers.stream().map(h -> new Entry(h.journalHeaderId(),h.journalNo(),h.journalDate(),h.journalType(),
                h.status(),h.validationRunId(),h.description(),h.debitTotal(),h.creditTotal(),List.copyOf(lines.getOrDefault(h.journalHeaderId(),List.of())))).toList();
        return new JournalExplorer(PageResponse.of(contracts,contractPage,size,contractCount,"contractNo,asc"),
                PageResponse.of(entries,page,size,journalCount,"journalDate,desc"),selected,totals);
    }
    private long count(String sql, MapSqlParameterSource parameters) { Long value=jdbc.queryForObject(sql,parameters,Long.class); return value == null ? 0 : value; }
    private static Entry entry(ResultSet rs, List<Line> lines) throws SQLException {
        return new Entry(rs.getLong("journal_header_id"),rs.getString("journal_no"),rs.getObject("journal_date",LocalDate.class),
                rs.getString("journal_type"),rs.getString("status"),rs.getObject("validation_run_id",Long.class),rs.getString("description"),
                rs.getBigDecimal(DEBIT_TOTAL),rs.getBigDecimal(CREDIT_TOTAL),lines);
    }
    private static String blank(String text) { return text == null || text.isBlank() ? null : text.trim(); }
    private static String escape(String text) { return text.replace("\\","\\\\").replace("%","\\%").replace("_","\\_"); }
}
