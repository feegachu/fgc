package com.susukkang.fgc.agenthome.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * FUN-086 설계사 홈 조회. 모든 쿼리는 본인 계약(최초 연결 설계사)만 읽는다.
 * 범위 조건은 {@code AgentDataScopeService.requireContract} 와 같은 식이다.
 */
@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentHomeRepository {

    private static final String SCOPED = "c.agent_id = :agentId\n";

    private final NamedParameterJdbcTemplate jdbc;

    /** 포트폴리오 모집단 한 줄. 모든 분포(보험사·월별·카테고리·상태)가 같은 줄 집합에서 나온다. */
    public record PortfolioRow(long contractId, long insurerId, String insurerName, LocalDate contractDate,
                               BigDecimal monthlyEquivalentFirstPremium, String productGroupCode,
                               String currentStatus, String statusAsOf) {
    }

    public record Candidate(long contractId, String contractNo, String insurerName, String productName,
                            String currentStatus, boolean followUp, boolean statusEvent, boolean heldLine,
                            LocalDate nextCheckDate, OffsetDateTime lastEventEffectiveAt, int maxEventSeq,
                            String lastCheckResult, OffsetDateTime lastCheckedAt, LocalDate lastNextCheckDate,
                            BigDecimal expectedAmount, BigDecimal heldAmount, long heldCount, long total) {
    }

    public record StoredCheck(long checkId, OffsetDateTime checkedAt, long contractId, String contextVersion,
                              String requestHash) {
    }

    public Optional<LocalDate> firstContractDate(long agentId) {
        return jdbc.query("SELECT min(c.contract_date) FROM fgc.insurance_contract c WHERE " + SCOPED,
                new MapSqlParameterSource("agentId", agentId),
                (rs, row) -> rs.getObject(1, LocalDate.class)).stream().filter(java.util.Objects::nonNull).findFirst();
    }

    public List<PortfolioRow> portfolio(long agentId, LocalDate from, LocalDate to,
                                        OffsetDateTime asOfEnd, String insurerType, Long insurerId) {
        StringBuilder sql = new StringBuilder("""
                SELECT c.contract_id, c.insurer_id, i.insurer_name, c.contract_date, c.monthly_equivalent_first_premium,
                       p.product_group_code, c.current_status,
                       (SELECT e.new_status FROM fgc.contract_status_event e
                         WHERE e.contract_id = c.contract_id AND e.effective_at < :asOfEnd
                         ORDER BY e.effective_at DESC, e.event_seq DESC LIMIT 1) AS status_as_of
                  FROM fgc.insurance_contract c
                  JOIN fgc.insurer i ON i.insurer_id = c.insurer_id
                  LEFT JOIN fgc.product_offering o ON o.product_offering_id = c.product_offering_id
                  LEFT JOIN fgc.product p ON p.product_id = o.product_id
                 WHERE c.contract_date BETWEEN :from AND :to AND
                """).append(SCOPED);
        MapSqlParameterSource params = new MapSqlParameterSource("agentId", agentId)
                .addValue("from", from).addValue("to", to).addValue("asOfEnd", asOfEnd);
        if (insurerType != null) {
            sql.append(" AND i.insurer_type = :insurerType");
            params.addValue("insurerType", insurerType);
        }
        if (insurerId != null) {
            sql.append(" AND c.insurer_id = :insurerId");
            params.addValue("insurerId", insurerId);
        }
        sql.append(" ORDER BY c.contract_id");
        return jdbc.query(sql.toString(), params, (rs, row) -> new PortfolioRow(rs.getLong(1), rs.getLong(2),
                rs.getString(3), rs.getObject(4, LocalDate.class), rs.getBigDecimal(5), rs.getString(6),
                rs.getString(7), rs.getString(8)));
    }

    /** asOf 월의 예정(PLANNED) 스케줄 행. 보류(HOLD)와 상태가 달라 같은 행이 겹치지 않는다. */
    public ExpectedSum expected(long agentId, LocalDate month) {
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(sl.expected_amount), 0), count(*)
                  FROM fgc.schedule_line sl
                  JOIN fgc.schedule_header sh ON sh.schedule_header_id = sl.schedule_header_id
                 WHERE sl.beneficiary_agent_id = :agentId AND sh.payment_stage = 'GA_TO_FC'
                   AND sh.schedule_purpose = 'OPERATIONAL' AND sh.active_yn
                   AND sl.line_status = 'PLANNED' AND sl.due_month = :month
                """, new MapSqlParameterSource("agentId", agentId).addValue("month", month),
                (rs, row) -> new ExpectedSum(rs.getBigDecimal(1), rs.getLong(2)));
    }

    public record ExpectedSum(BigDecimal amount, long count) {
    }

    /**
     * 확인할 계약 후보. 최신 확인(checked_at, id 내림차순) 이후의 새 사건·보류만 다시 후보가 된다.
     * 정렬: 후속일↑(후속 확인 사유가 있을 때만) → 마지막 사건 효력일↓ → 계약 ID↑.
     */
    public List<Candidate> candidates(long agentId, LocalDate asOf, OffsetDateTime asOfEnd,
                                      OffsetDateTime recentFrom, List<String> eventStatuses, int limit) {
        String sql = """
                WITH last_check AS (
                  SELECT DISTINCT ON (k.contract_id) k.contract_id, k.check_result, k.next_check_date, k.checked_at,
                         k.checked_event_seq
                    FROM fgc.agent_contract_check k
                   WHERE k.agent_id = :agentId
                   ORDER BY k.contract_id, k.checked_at DESC, k.agent_contract_check_id DESC),
                last_event AS (
                  SELECT DISTINCT ON (e.contract_id) e.contract_id, e.effective_at
                    FROM fgc.contract_status_event e WHERE e.effective_at < :asOfEnd
                   ORDER BY e.contract_id, e.effective_at DESC, e.event_seq DESC),
                held AS (
                  SELECT sh.contract_id, count(*) AS held_count, sum(sl.expected_amount) AS held_amount,
                         max(sl.created_at) AS latest_created_at
                    FROM fgc.schedule_line sl JOIN fgc.schedule_header sh ON sh.schedule_header_id = sl.schedule_header_id
                   WHERE sl.beneficiary_agent_id = :agentId AND sh.payment_stage = 'GA_TO_FC'
                     AND sh.schedule_purpose = 'OPERATIONAL' AND sh.active_yn AND sl.line_status = 'HOLD'
                   GROUP BY sh.contract_id),
                expected AS (
                  SELECT sh.contract_id, sum(sl.expected_amount) AS amount
                    FROM fgc.schedule_line sl JOIN fgc.schedule_header sh ON sh.schedule_header_id = sl.schedule_header_id
                   WHERE sl.beneficiary_agent_id = :agentId AND sh.payment_stage = 'GA_TO_FC'
                     AND sh.schedule_purpose = 'OPERATIONAL' AND sh.active_yn AND sl.line_status = 'PLANNED'
                     AND sl.due_month = :month
                   GROUP BY sh.contract_id),
                cand AS (
                  SELECT c.contract_id, c.contract_no, i.insurer_name, p.product_name, c.current_status,
                         (lc.check_result = 'FOLLOW_UP' AND lc.next_check_date <= :asOf) AS follow_up,
                         -- 확인 이후 새 사건은 접수일시(소급 입력 가능)가 아니라 사건 순번으로 판정한다.
                         -- 확인 기록이 없으면 최근 N일 안의 사건만 후보로 본다.
                         EXISTS (SELECT 1 FROM fgc.contract_status_event e
                                  WHERE e.contract_id = c.contract_id AND e.new_status IN (:eventStatuses)
                                    AND e.effective_at < :asOfEnd
                                    AND ((lc.contract_id IS NULL AND e.effective_at >= :recentFrom)
                                         OR e.event_seq > lc.checked_event_seq)) AS status_event,
                         -- 미해결 보류: HOLD 회차가 있고, 확인이 없거나 확인 뒤 새 사건·새 회차가 있을 때.
                         -- 보류는 기존 PLANNED 회차가 사건으로 HOLD 로 바뀌어 생기므로 새 사건 순번으로 잡는다.
                         (hl.held_count > 0 AND (lc.contract_id IS NULL OR hl.latest_created_at > lc.checked_at
                              OR EXISTS (SELECT 1 FROM fgc.contract_status_event e
                                          WHERE e.contract_id = c.contract_id AND e.event_seq > lc.checked_event_seq
                                            AND e.effective_at < :asOfEnd))) AS held_line,
                         lc.next_check_date, le.effective_at AS last_effective_at,
                         COALESCE((SELECT max(e.event_seq) FROM fgc.contract_status_event e
                                    WHERE e.contract_id = c.contract_id), 0) AS max_seq,
                         lc.check_result, lc.checked_at, lc.next_check_date AS last_next,
                         COALESCE(ex.amount, 0) AS expected_amount, COALESCE(hl.held_amount, 0) AS held_amount,
                         COALESCE(hl.held_count, 0) AS held_count
                    FROM fgc.insurance_contract c
                    JOIN fgc.insurer i ON i.insurer_id = c.insurer_id
                    LEFT JOIN fgc.product_offering o ON o.product_offering_id = c.product_offering_id
                    LEFT JOIN fgc.product p ON p.product_id = o.product_id
                    LEFT JOIN last_check lc ON lc.contract_id = c.contract_id
                    LEFT JOIN last_event le ON le.contract_id = c.contract_id
                    LEFT JOIN held hl ON hl.contract_id = c.contract_id
                    LEFT JOIN expected ex ON ex.contract_id = c.contract_id
                   WHERE c.contract_date <= :asOf AND
                """ + SCOPED + """
                )
                SELECT cand.*, count(*) OVER () AS total
                  FROM cand
                 WHERE follow_up OR status_event OR held_line
                 ORDER BY CASE WHEN follow_up THEN next_check_date END ASC NULLS LAST,
                          last_effective_at DESC NULLS LAST, contract_id ASC
                 LIMIT :limit
                """;
        MapSqlParameterSource params = new MapSqlParameterSource("agentId", agentId)
                .addValue("asOf", asOf).addValue("asOfEnd", asOfEnd).addValue("recentFrom", recentFrom)
                .addValue("eventStatuses", eventStatuses).addValue("limit", limit)
                .addValue("month", asOf.withDayOfMonth(1));
        return jdbc.query(sql, params, (rs, row) -> new Candidate(rs.getLong("contract_id"), rs.getString("contract_no"),
                rs.getString("insurer_name"), rs.getString("product_name"), rs.getString("current_status"),
                rs.getBoolean("follow_up"), rs.getBoolean("status_event"), rs.getBoolean("held_line"),
                rs.getObject("next_check_date", LocalDate.class), rs.getObject("last_effective_at", OffsetDateTime.class),
                rs.getInt("max_seq"), rs.getString("check_result"), rs.getObject("checked_at", OffsetDateTime.class),
                rs.getObject("last_next", LocalDate.class), rs.getBigDecimal("expected_amount"),
                rs.getBigDecimal("held_amount"), rs.getLong("held_count"), rs.getLong("total")));
    }

    /** 계약의 마지막 사건 순번(없으면 0). contextVersion 의 재료다. */
    public int maxEventSeq(long contractId) {
        Integer seq = jdbc.queryForObject(
                "SELECT COALESCE(max(event_seq), 0) FROM fgc.contract_status_event WHERE contract_id = :id",
                new MapSqlParameterSource("id", contractId), Integer.class);
        return seq == null ? 0 : seq;
    }

    public Optional<StoredCheck> findByIdempotencyKey(long agentId, String key) {
        return jdbc.query("""
                SELECT agent_contract_check_id, checked_at, contract_id, context_version, request_hash
                  FROM fgc.agent_contract_check WHERE agent_id = :agentId AND idempotency_key = :key
                """, new MapSqlParameterSource("agentId", agentId).addValue("key", key),
                AgentHomeRepository::stored).stream().findFirst();
    }

    /** 같은 키의 동시 요청을 트랜잭션 끝까지 막는다. 잠그지 못하면 처리 중인 요청이 있다. */
    public boolean tryLock(long agentId, String key) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended(:k, 0))",
                new MapSqlParameterSource("k", "agent-contract-check:" + agentId + ":" + key), Boolean.class));
    }

    @Transactional
    public StoredCheck insert(long agentId, long contractId, String result, LocalDate nextCheckDate, String memo,
                              String key, String hash, String contextVersion, int checkedEventSeq) {
        return jdbc.query("""
                INSERT INTO fgc.agent_contract_check
                    (agent_id, contract_id, check_result, next_check_date, memo, idempotency_key, request_hash, context_version,
                     checked_event_seq)
                VALUES (:agentId, :contractId, :result, :next, :memo, :key, :hash, :version, :seq)
                RETURNING agent_contract_check_id, checked_at, contract_id, context_version, request_hash
                """, new MapSqlParameterSource("agentId", agentId).addValue("contractId", contractId)
                        .addValue("result", result).addValue("next", nextCheckDate).addValue("memo", memo)
                        .addValue("key", key).addValue("hash", hash).addValue("version", contextVersion)
                        .addValue("seq", checkedEventSeq),
                AgentHomeRepository::stored).get(0);
    }

    private static StoredCheck stored(ResultSet rs, int row) throws SQLException {
        return new StoredCheck(rs.getLong(1), rs.getObject(2, OffsetDateTime.class), rs.getLong(3), rs.getString(4),
                rs.getString(5));
    }
}
