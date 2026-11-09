package com.susukkang.fgc.clawback.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** FUN-084 통지서 발행 요청과 PDF 표시용 기준정보. 계산 금액·비율은 계산판 snapshot에서만 읽는다. */
@Repository
@RequiredArgsConstructor
public class ClawbackNoticeDocumentRepository {
    private final JdbcTemplate jdbc;

    public boolean tryLockRequest(String key) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))", Boolean.class, "clawback-notice-request:" + key));
    }

    public record StoredRequest(long requestedBy, long caseId, String requestHash, long documentId) { }

    public Optional<StoredRequest> findRequest(String key) {
        return jdbc.query("""
                SELECT requested_by, clawback_case_id, request_hash, generated_document_id
                  FROM fgc.clawback_notice_document_request WHERE idempotency_key = ?
                """, (rs, n) -> new StoredRequest(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4)), key)
                .stream().findFirst();
    }

    public void saveRequest(String key, long userId, long caseId, String hash, long documentId) {
        jdbc.update("""
                INSERT INTO fgc.clawback_notice_document_request
                    (idempotency_key, requested_by, clawback_case_id, request_hash, generated_document_id)
                VALUES (?, ?, ?, ?, ?)
                """, key, userId, caseId, hash, documentId);
    }

    public record CaseRow(long caseId, Long candidateId, String status) { }

    /**
     * case 행을 잠근다. 계산(IF-API-85)도 같은 행을 잠그므로 발행 중에 새 계산판이 끼어들지 않는다.
     * 잠금만 하고 상태·통지일·확정액은 바꾸지 않는다.
     */
    public Optional<CaseRow> lockCase(long caseId) {
        return jdbc.query("""
                SELECT clawback_case_id, candidate_id, status FROM fgc.clawback_case
                 WHERE clawback_case_id = ? FOR UPDATE
                """, (rs, n) -> new CaseRow(rs.getLong(1), rs.getObject(2, Long.class), rs.getString(3)), caseId)
                .stream().findFirst();
    }

    public boolean caseExists(long caseId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM fgc.clawback_case WHERE clawback_case_id = ?)", Boolean.class, caseId));
    }

    public record LatestCalculation(int versionNo, String sourceVersion, String snapshotJson) { }

    public Optional<LatestCalculation> findLatestCalculation(long caseId) {
        return jdbc.query("""
                SELECT version_no, source_version, result_snapshot::text FROM fgc.clawback_calculation
                 WHERE clawback_case_id = ? ORDER BY version_no DESC LIMIT 1
                """, (rs, n) -> new LatestCalculation(rs.getInt(1), rs.getString(2), rs.getString(3)), caseId)
                .stream().findFirst();
    }

    /** 계약자 개인정보는 읽지 않는다. 계약번호·보험회사·상품·설계사·원인만 표시한다. */
    public record NoticeHeader(String contractNo, String insurerName, String productName, String agentCode,
                               String agentName, String triggerStatus, OffsetDateTime triggerEffectiveAt,
                               String policyCode, String policyName, int policyVersionNo,
                               String gaName, String branchName, String teamName) {
        public NoticeHeader(String contractNo, String insurerName, String productName, String agentCode,
                            String agentName, String triggerStatus, OffsetDateTime triggerEffectiveAt,
                            String policyCode, String policyName, int policyVersionNo) {
            this(contractNo, insurerName, productName, agentCode, agentName, triggerStatus, triggerEffectiveAt,
                    policyCode, policyName, policyVersionNo, null, null, null);
        }
    }

    public NoticeHeader findHeader(long caseId, long policyVersionId) {
        return findHeader(caseId, policyVersionId, null);
    }

    public NoticeHeader findHeader(long caseId, long policyVersionId, Long originalPaymentId) {
        return jdbc.queryForObject("""
                SELECT ic.contract_no, i.insurer_name, p.product_name, a.agent_code, a.agent_name,
                       se.new_status, se.effective_at, pv.policy_code, pv.policy_name, pv.version_no,
                       org.ga_name, org.branch_name, org.team_name
                  FROM fgc.clawback_case cc
                  JOIN fgc.insurance_contract ic ON ic.contract_id = cc.contract_id
                  JOIN fgc.insurer i ON i.insurer_id = ic.insurer_id
                  JOIN fgc.product_offering po ON po.product_offering_id = ic.product_offering_id
                  JOIN fgc.product p ON p.product_id = po.product_id
                  JOIN fgc.agent a ON a.agent_id = COALESCE((SELECT recipient_agent_id FROM fgc.commission_transaction
                      WHERE commission_transaction_id = ?), ic.agent_id)
                  LEFT JOIN LATERAL (
                    WITH RECURSIVE ancestors AS (
                      SELECT organization_id,parent_id,organization_name,organization_type,ARRAY[organization_id] AS path
                        FROM fgc.organization WHERE organization_id=a.organization_id
                      UNION ALL
                      SELECT p.organization_id,p.parent_id,p.organization_name,p.organization_type,c.path||p.organization_id
                        FROM fgc.organization p JOIN ancestors c ON p.organization_id=c.parent_id
                        WHERE NOT p.organization_id=ANY(c.path)
                    )
                    SELECT max(organization_name) FILTER (WHERE organization_type='GA') AS ga_name,
                           max(organization_name) FILTER (WHERE organization_type='BRANCH') AS branch_name,
                           max(organization_name) FILTER (WHERE organization_type='TEAM') AS team_name
                      FROM ancestors
                  ) org ON true
                  LEFT JOIN fgc.contract_status_event se ON se.contract_status_event_id = cc.trigger_status_event_id
                  JOIN fgc.policy_version pv ON pv.policy_version_id = ?
                 WHERE cc.clawback_case_id = ?
                """, (rs, n) -> new NoticeHeader(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getObject(7, OffsetDateTime.class), rs.getString(8),
                rs.getString(9), rs.getInt(10), rs.getString(11), rs.getString(12), rs.getString(13)), originalPaymentId, policyVersionId, caseId);
    }

    public void savePaymentScope(long documentId, long originalPaymentId) {
        jdbc.update("INSERT INTO fgc.clawback_notice_payment_scope VALUES (?, ?)", documentId, originalPaymentId);
    }

    public Long findPaymentScope(long documentId) {
        return jdbc.query("SELECT original_payment_id FROM fgc.clawback_notice_payment_scope WHERE generated_document_id=?",
                (rs, n) -> rs.getLong(1), documentId).stream().findFirst().orElse(null);
    }

    public Map<Long, String> findItemNames() {
        return jdbc.query("SELECT commission_item_id, item_name FROM fgc.commission_item",
                (rs, n) -> Map.entry(rs.getLong(1), rs.getString(2)))
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
