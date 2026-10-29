package com.susukkang.fgc.clawback.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.clawback.dto.ClawbackCandidateDetailResponse.Calculation;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** FUN-055 계산판·case·라인 저장. 호출자는 후보 잠금을 잡은 같은 트랜잭션에서 사용한다. */
@Repository
@RequiredArgsConstructor
public class ClawbackCalculationRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public boolean tryLockRequest(String key) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))", Boolean.class, "clawback-calc-request:" + key));
    }

    /** 같은 후보의 계산·원천 관측을 직렬화한다. 대기 뒤 다음 문장은 새 커밋 snapshot을 읽는다. */
    public void lockCandidate(long candidateId) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "clawback-candidate:" + candidateId);
    }

    public record StoredRequest(long requestedBy, long candidateId, String requestHash, long calculationId) { }

    public Optional<StoredRequest> findRequest(String key) {
        return jdbc.query("""
                SELECT requested_by, candidate_id, request_hash, clawback_calculation_id
                  FROM fgc.clawback_calculation_request WHERE idempotency_key = ?
                """, (rs, n) -> new StoredRequest(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4)), key)
                .stream().findFirst();
    }

    public void saveRequest(String key, long userId, long candidateId, String hash, long calculationId) {
        jdbc.update("""
                INSERT INTO fgc.clawback_calculation_request
                    (idempotency_key, requested_by, candidate_id, request_hash, clawback_calculation_id)
                VALUES (?, ?, ?, ?, ?)
                """, key, userId, candidateId, hash, calculationId);
    }

    public record StoredCalculation(long calculationId, Calculation calculation) { }

    public Optional<StoredCalculation> findCalculation(long calculationId) {
        return jdbc.query("SELECT clawback_calculation_id, result_snapshot::text FROM fgc.clawback_calculation "
                        + "WHERE clawback_calculation_id = ?",
                (rs, n) -> new StoredCalculation(rs.getLong(1), decode(rs.getString(2))), calculationId).stream().findFirst();
    }

    /** 같은 후보·원천 버전·정책·산식 버전의 판. 있으면 새 판을 만들지 않고 그대로 돌려준다. */
    public Optional<StoredCalculation> findByInput(long candidateId, String sourceVersion, long policyVersionId,
                                                   String calcRuleVersion) {
        return jdbc.query("""
                SELECT clawback_calculation_id, result_snapshot::text FROM fgc.clawback_calculation
                 WHERE candidate_id = ? AND source_version = ? AND policy_version_id = ? AND calc_rule_version = ?
                """, (rs, n) -> new StoredCalculation(rs.getLong(1), decode(rs.getString(2))),
                candidateId, sourceVersion, policyVersionId, calcRuleVersion).stream().findFirst();
    }

    public record Rule(long ruleId, Long insurerId, Long productOfferingId, Long commissionItemId,
                       int monthFrom, int monthTo, String basisCode, BigDecimal ratePct, String condition) { }

    public List<Rule> findRules(long policyVersionId) {
        return jdbc.query("""
                SELECT clawback_rule_id, insurer_id, product_offering_id, commission_item_id,
                       elapsed_month_from, elapsed_month_to, clawback_basis_code, clawback_rate_pct,
                       condition_expression::text
                  FROM fgc.clawback_rule WHERE policy_version_id = ? ORDER BY clawback_rule_id
                """, (rs, n) -> new Rule(rs.getLong(1), rs.getObject(2, Long.class), rs.getObject(3, Long.class),
                rs.getObject(4, Long.class), rs.getInt(5), rs.getInt(6), rs.getString(7), rs.getBigDecimal(8),
                rs.getString(9)), policyVersionId);
    }

    public record CaseRow(long caseId, String status) { }

    /** 후보의 case를 잠그고 반환한다. 없으면 선택 정책으로 DETECTED case를 만든다. */
    public CaseRow lockOrCreateCase(long candidateId, long contractId, Long statusEventId, long policyVersionId,
                                    long userId) {
        jdbc.update("""
                INSERT INTO fgc.clawback_case (contract_id, trigger_status_event_id, policy_version_id, case_no,
                                               status, created_by, candidate_id)
                VALUES (?, ?, ?, ?, 'DETECTED', ?, ?)
                ON CONFLICT (candidate_id) WHERE candidate_id IS NOT NULL DO NOTHING
                """, contractId, statusEventId, policyVersionId, "CLW-C" + candidateId, userId, candidateId);
        return jdbc.queryForObject("""
                SELECT clawback_case_id, status FROM fgc.clawback_case WHERE candidate_id = ? FOR UPDATE
                """, (rs, n) -> new CaseRow(rs.getLong(1), rs.getString(2)), candidateId);
    }

    public int nextVersion(long caseId) {
        Integer max = jdbc.queryForObject(
                "SELECT max(version_no) FROM fgc.clawback_calculation WHERE clawback_case_id = ?", Integer.class, caseId);
        return max == null ? 1 : max + 1;
    }

    public long insertCalculation(long caseId, long candidateId, int versionNo, String sourceVersion,
                                  long policyVersionId, String calcRuleVersion, int elapsedMonths, BigDecimal amount,
                                  String reason, Calculation snapshot, long userId, OffsetDateTime calculatedAt) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.clawback_calculation (clawback_case_id, candidate_id, version_no, source_version,
                    policy_version_id, calc_rule_version, elapsed_months, amount, reason, result_snapshot,
                    calculated_by, calculated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                RETURNING clawback_calculation_id
                """, Long.class, caseId, candidateId, versionNo, sourceVersion, policyVersionId, calcRuleVersion,
                elapsedMonths, amount, reason, encode(snapshot), userId, Timestamp.from(calculatedAt.toInstant()));
    }

    /** clawback_line은 case의 최신 판 결과만 담는다. 과거 판의 행은 계산판 snapshot에 남아 있다. */
    public void replaceLines(long caseId, List<Map<String, Object>> lines) {
        jdbc.update("DELETE FROM fgc.clawback_line WHERE clawback_case_id = ?", caseId);
        int lineNo = 1;
        for (Map<String, Object> line : lines) {
            jdbc.update("""
                    INSERT INTO fgc.clawback_line (clawback_case_id, line_no, transaction_attribution_id,
                        clawback_rule_id, originally_paid_amount, applied_rate_pct, clawback_amount, calculation_snapshot)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                    """, caseId, lineNo++, line.get("transactionAttributionId"), line.get("clawbackRuleId"),
                    line.get("basisAmount"), line.get("appliedRatePct"), line.get("clawbackAmount"), encode(line));
        }
    }

    public void markCalculated(long caseId, long policyVersionId, BigDecimal amount) {
        jdbc.update("""
                UPDATE fgc.clawback_case SET status = 'CALCULATED', policy_version_id = ?, calculated_amount = ?
                 WHERE clawback_case_id = ?
                """, policyVersionId, amount, caseId);
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("환수 계산판을 저장할 수 없습니다.", e);
        }
    }

    private Calculation decode(String json) {
        try {
            return objectMapper.readerFor(Calculation.class)
                    .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("환수 계산판을 읽을 수 없습니다.", e);
        }
    }
}
