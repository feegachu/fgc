package com.susukkang.fgc.acquisitioncost.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.acquisitioncost.dto.AcquisitionCostCheckResponse;
import com.susukkang.fgc.acquisitioncost.dto.AcquisitionCostCheckResponse.AcquisitionCostItem;
import com.susukkang.fgc.acquisitioncost.dto.AcquisitionCostCheckResponse.AcquisitionCostPolicyRef;
import com.susukkang.fgc.acquisitioncost.dto.AcquisitionCostCheckSearchCondition;
import com.susukkang.fgc.acquisitioncost.service.ContractAcquisitionCostValidator.Line;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.PolicySourceClass;
import com.susukkang.fgc.common.web.ListSort;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 설명 : FUN-062 검증 입력(계약·정책·예정 회차)과 결과를 읽고 쓴다. 결과는 calculation_snapshot jsonb에 저장한 문서를 그대로 돌려주므로
 * 네이티브 SQL을 쓴다(CONTRIBUTING §20). 결과 행은 INSERT만 한다.
 */
@Repository
@RequiredArgsConstructor
public class AcquisitionCostCheckRepository {
    public static final Map<String, String> SORTABLE = Map.of("checkedAt", "a.created_at", "contractNo", "c.contract_no");
    public static final ListSort DEFAULT_SORT = new ListSort("checkedAt", false);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public record ContractFacts(long contractId, String contractNo, String insurerName, String productName,
                                String protectionType, String offeringVersion, String basicDocumentVersion,
                                LocalDate basicDocumentDate, LocalDate salesStartDate, LocalDate contractDate,
                                String feeRegimeCode, BigDecimal monthlyEquivalentFirstPremium) {
    }

    public record StoredRequest(long requestedBy, String requestHash, long checkId) {
    }

    public record Parameter(String value, String description) {
    }

    public record ScheduleRef(long scheduleHeaderId, int scheduleVersionNo) {
    }

    public record SearchResult(List<AcquisitionCostCheckResponse> items, long total) {
    }

    /** 같은 키의 요청을 처리 중이면 false. 잠금은 트랜잭션이 끝나면 풀린다. */
    public boolean tryLockRequest(String key) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))", Boolean.class, "acquisition-cost-request:" + key));
    }

    public Optional<StoredRequest> findRequest(String key) {
        return jdbc.query("""
                SELECT requested_by, request_hash, acquisition_cost_check_id
                  FROM fgc.acquisition_cost_check WHERE idempotency_key = ?
                """, (rs, n) -> new StoredRequest(rs.getLong(1), rs.getString(2), rs.getLong(3)), key).stream().findFirst();
    }

    public Optional<ContractFacts> findContract(long contractId) {
        return jdbc.query("""
                SELECT c.contract_id, c.contract_no, i.insurer_name, p.product_name, p.protection_type,
                       po.offering_version, po.basic_document_version, po.basic_document_date, po.sales_start_date,
                       c.contract_date, po.fee_regime_code, c.monthly_equivalent_first_premium
                  FROM fgc.insurance_contract c
                  JOIN fgc.insurer i ON i.insurer_id = c.insurer_id
                  JOIN fgc.product_offering po ON po.product_offering_id = c.product_offering_id
                  JOIN fgc.product p ON p.product_id = po.product_id
                 WHERE c.contract_id = ?
                """, (rs, n) -> new ContractFacts(rs.getLong("contract_id"), rs.getString("contract_no"),
                rs.getString("insurer_name"), rs.getString("product_name"), rs.getString("protection_type"),
                rs.getString("offering_version"), rs.getString("basic_document_version"),
                localDate(rs, "basic_document_date"), localDate(rs, "sales_start_date"),
                localDate(rs, "contract_date"), rs.getString("fee_regime_code"),
                rs.getBigDecimal("monthly_equivalent_first_premium")), contractId).stream().findFirst();
    }

    public AcquisitionCostPolicyRef findPolicy(long policyVersionId) {
        return jdbc.queryForObject("""
                SELECT policy_version_id, policy_code, version_no, policy_name, source_class, regulation_refs, source_refs
                  FROM fgc.policy_version WHERE policy_version_id = ?
                """, (rs, n) -> {
            String sourceClass = rs.getString("source_class");
            return new AcquisitionCostPolicyRef(rs.getLong("policy_version_id"), rs.getString("policy_code"), rs.getInt("version_no"),
                    rs.getString("policy_name"), sourceClass, PolicySourceClass.valueOf(sourceClass).label(),
                    textArray(rs, "regulation_refs"), textArray(rs, "source_refs"));
        }, policyVersionId);
    }

    /** 키별 값. jsonb 스칼라를 글자로 읽는다. 없는 키는 결과에 없다. */
    public Map<String, Parameter> findParameters(long policyVersionId, List<String> keys) {
        return jdbc.query("""
                SELECT parameter_key, parameter_value #>> '{}', description
                  FROM fgc.policy_parameter
                 WHERE policy_version_id = ? AND parameter_key = ANY (?)
                """, ps -> {
            ps.setLong(1, policyVersionId);
            ps.setArray(2, ps.getConnection().createArrayOf("varchar", keys.toArray()));
        }, rs -> {
            Map<String, Parameter> found = new java.util.HashMap<>();
            while (rs.next()) found.put(rs.getString(1), new Parameter(rs.getString(2), rs.getString(3)));
            return found;
        });
    }

    /** 운영 스케줄과 §4-4-3의 4년·7년 예정판(PHASE2 시나리오)만 본다. 비교·다른 시나리오는 판정 대상이 아니다. */
    public List<ScheduleRef> findActiveSchedules(long contractId, PaymentStage stage, String regime, long policyVersionId) {
        return jdbc.query("""
                SELECT schedule_header_id, schedule_version_no
                  FROM fgc.schedule_header
                 WHERE contract_id = ? AND payment_stage = ? AND schedule_regime = ? AND policy_version_id = ?
                   AND active_yn AND status <> 'CANCELLED'
                   AND (schedule_purpose = 'OPERATIONAL'
                        OR (schedule_purpose = 'SIMULATION'
                            AND scenario_code = 'PHASE2_' || schedule_regime || '_' || policy_version_id))
                 ORDER BY schedule_header_id
                """, (rs, n) -> new ScheduleRef(rs.getLong(1), rs.getInt(2)), contractId, stage.name(), regime, policyVersionId);
    }

    /**
     * 제외 증빙은 그 회차에 귀속된 확정 지급의 제4-32조제7항제3호 제외 기록이 모두 증빙을 갖고 귀속 합계가 회차 금액 이상일 때만 인정한다.
     * shortcut: 재생성 전 판의 귀속은 따라가지 않는다. 제외되지 않아 검토 필요로만 틀리며, 4년·7년 회차의 지급 확정이 열리면 계보 조회를 붙인다.
     */
    public List<Line> findLines(long scheduleHeaderId) {
        return jdbc.query("""
                SELECT sl.schedule_line_id, sl.installment_no, ci.item_code, ci.item_name, cr.fee_component_type,
                       sl.expected_amount,
                       (SELECT CASE WHEN COUNT(*) > 0 AND BOOL_AND(NULLIF(BTRIM(ta.evidence_ref), '') IS NOT NULL)
                                         AND SUM(ta.attributed_amount) >= sl.expected_amount
                                    THEN MIN(NULLIF(BTRIM(ta.evidence_ref), '')) END
                          FROM fgc.transaction_attribution ta
                          JOIN fgc.commission_transaction ct
                            ON ct.commission_transaction_id = ta.commission_transaction_id
                         WHERE ta.schedule_line_id = sl.schedule_line_id
                           AND ct.status = 'CONFIRMED'
                           AND ct.commission_item_id = sl.commission_item_id
                           AND ta.inclusion_status_snapshot = 'EXCLUDED'
                           AND ta.exclusion_type_snapshot = 'NEW_AGENT_SUPPORT') AS evidence_ref
                  FROM fgc.schedule_line sl
                  JOIN fgc.commission_item ci ON ci.commission_item_id = sl.commission_item_id
                  LEFT JOIN fgc.commission_rule cr ON cr.commission_rule_id = sl.source_commission_rule_id
                 WHERE sl.schedule_header_id = ? AND sl.line_status <> 'CANCELLED'
                 ORDER BY sl.installment_no, sl.line_no
                """, (rs, n) -> new Line(rs.getLong("schedule_line_id"), rs.getInt("installment_no"),
                rs.getString("item_code"), rs.getString("item_name"), rs.getString("fee_component_type"),
                rs.getBigDecimal("expected_amount"), rs.getString("evidence_ref")), scheduleHeaderId);
    }

    public long insert(AcquisitionCostCheckResponse result, Long policyVersionId, String key, String hash, long userId) {
        AcquisitionCostItem upfront = result.upfront();
        AcquisitionCostItem maintenance = result.maintenance();
        long id = jdbc.queryForObject("""
                INSERT INTO fgc.acquisition_cost_check
                    (contract_id, payment_stage, policy_version_id, upfront_limit_amount, upfront_total_amount,
                     maintenance_limit_amount, maintenance_total_amount, indirect_support_amount, result_status,
                     calculation_snapshot, requested_by, idempotency_key, request_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                RETURNING acquisition_cost_check_id
                """, Long.class, result.contractId(), result.paymentStage().name(), policyVersionId,
                upfront == null ? null : upfront.limitAmount(), upfront == null ? null : upfront.calculatedAmount(),
                maintenance == null ? null : maintenance.limitAmount(),
                maintenance == null ? null : maintenance.calculatedAmount(), result.indirectSupportAmount(),
                result.resultStatus().name(), json(result), userId, key, hash);
        for (AcquisitionCostItem item : Arrays.asList(upfront, maintenance)) {
            if (item == null) continue;
            jdbc.update("""
                    INSERT INTO fgc.acquisition_cost_check_detail
                        (acquisition_cost_check_id, check_type, actual_amount, limit_amount, usage_pct, result_status,
                         decision_reason, calculation_snapshot)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    """, id, item.limitCode(), item.calculatedAmount(), item.limitAmount(), item.usagePct(),
                    item.status().name(), item.reason(), json(item));
        }
        return id;
    }

    public Optional<AcquisitionCostCheckResponse> findById(long id) {
        return jdbc.query("""
                SELECT acquisition_cost_check_id, calculation_snapshot::text, created_at
                  FROM fgc.acquisition_cost_check WHERE acquisition_cost_check_id = ?
                """, stored(), id).stream().findFirst();
    }

    // S2077: 코드에 정의한 조건식과 SORTABLE의 정렬식만 조립하고 사용자 입력은 모두 바인딩한다.
    @SuppressWarnings("java:S2077")
    public SearchResult search(AcquisitionCostCheckSearchCondition condition, ListSort sort, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (condition.getContractId() != null) {
            where.append(" AND a.contract_id = ?");
            params.add(condition.getContractId());
        }
        if (condition.getContractNo() != null && !condition.getContractNo().isBlank()) {
            // position은 LIKE 와일드카드를 글자 그대로 찾는다.
            where.append(" AND position(lower(?) IN lower(c.contract_no)) > 0");
            params.add(condition.getContractNo().strip());
        }
        if (condition.getInsurerId() != null) {
            where.append(" AND c.insurer_id = ?");
            params.add(condition.getInsurerId());
        }
        if (condition.getPaymentStage() != null) {
            where.append(" AND a.payment_stage = ?");
            params.add(condition.getPaymentStage().name());
        }
        if (condition.getResultStatus() != null) {
            where.append(" AND a.result_status = ?");
            params.add(condition.getResultStatus().name());
        }
        String from = " FROM fgc.acquisition_cost_check a JOIN fgc.insurance_contract c ON c.contract_id = a.contract_id";
        Long total = jdbc.queryForObject("SELECT count(*)" + from + where, Long.class, params.toArray());
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((long) (page - 1) * size);
        List<AcquisitionCostCheckResponse> items = jdbc.query(
                "SELECT a.acquisition_cost_check_id, a.calculation_snapshot::text, a.created_at" + from + where
                        + " ORDER BY " + SORTABLE.get(sort.key()) + " " + sort.direction()
                        + ", a.acquisition_cost_check_id DESC LIMIT ? OFFSET ?",
                stored(), pageParams.toArray());
        return new SearchResult(items, total == null ? 0 : total);
    }

    private RowMapper<AcquisitionCostCheckResponse> stored() {
        return (rs, n) -> {
            try {
                return objectMapper.readValue(rs.getString(2), AcquisitionCostCheckResponse.class).stored(rs.getLong(1),
                        rs.getObject(3, OffsetDateTime.class).atZoneSameInstant(SEOUL).toOffsetDateTime());
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("저장된 계약체결비용 검증 결과를 읽을 수 없습니다: " + rs.getLong(1), e);
            }
        };
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("계약체결비용 검증 결과를 저장할 수 없습니다.", e);
        }
    }

    private static LocalDate localDate(ResultSet rs, String column) throws SQLException {
        Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private static List<String> textArray(ResultSet rs, String column) throws SQLException {
        java.sql.Array value = rs.getArray(column);
        return value == null ? List.of() : List.of((String[]) value.getArray());
    }
}
