package com.susukkang.fgc.dataset;

import com.susukkang.fgc.dataset.Dar012Plan.ContractRow;
import com.susukkang.fgc.dataset.Dar012Plan.OrgRow;
import com.susukkang.fgc.dataset.Dar012Plan.PaymentRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DAR-012 기초 데이터를 한 트랜잭션으로 적재한다 (시드명세 §50, §22).
 * 스케줄·1,200%·분개·대사는 넣지 않는다. 지급 건은 DRAFT → 귀속 → 합계 검증 → CONFIRMED 순서를 따른다.
 * 기술키는 코드에 하드코딩하지 않고 업무코드로 조회한다(§0 원칙 7·8).
 */
@Slf4j
public class Dar012Loader {

    static final String SOURCE_PREFIX = "SYNTHETIC:DAR012:";
    private static final int BATCH_SIZE = 1000;
    private static final LocalDate ORG_EFFECTIVE_FROM = LocalDate.of(2026, 1, 1);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public Dar012Loader(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** 계획에 쓸 정책값. 승인된 ACTIVE 정책에서만 읽고 하나로 정해지지 않으면 중단한다. */
    public record PolicyRefs(Dar012Planner.PolicyInputs inputs, long policyVersionId, long capRuleItemId) {}

    public PolicyRefs readPolicy(LocalDate windowStart, LocalDate windowEnd) {
        List<Map<String, Object>> rates = jdbc.queryForList("""
                SELECT r.rate_pct, pv.policy_version_id
                  FROM commission_rule r
                  JOIN policy_version pv ON pv.policy_version_id = r.policy_version_id
                  JOIN commission_item ci ON ci.commission_item_id = r.commission_item_id
                 WHERE pv.policy_type = 'CURRENT_COMMISSION' AND pv.status = 'ACTIVE'
                   AND pv.effective_from <= ? AND (pv.effective_to IS NULL OR pv.effective_to >= ?)
                   AND r.payment_stage = 'INSURER_TO_GA' AND ci.item_code = 'BASE_COMMISSION'
                   AND r.installment_from = 1 AND r.calculation_type = 'RATE'
                   AND r.insurer_id IS NULL AND r.product_offering_id IS NULL
                """, Date.valueOf(windowStart), Date.valueOf(windowEnd));
        if (rates.size() != 1) {
            throw new IllegalStateException("원수사→GA 초회 기본수수료율 정책이 1건으로 정해지지 않는다: " + rates.size() + "건");
        }
        List<Map<String, Object>> caps = jdbc.queryForList("""
                SELECT crs.premium_multiplier, cri.cap_rule_item_id
                  FROM cap_rule_set crs
                  JOIN policy_version pv ON pv.policy_version_id = crs.policy_version_id
                  JOIN cap_rule_item cri ON cri.cap_rule_set_id = crs.cap_rule_set_id
                  JOIN commission_item ci ON ci.commission_item_id = cri.commission_item_id
                 WHERE pv.status = 'ACTIVE' AND crs.payment_stage = 'INSURER_TO_GA'
                   AND crs.insurer_id IS NULL AND crs.product_group_code IS NULL AND crs.channel_code IS NULL
                   AND crs.contract_date_from <= ? AND (crs.contract_date_to IS NULL OR crs.contract_date_to >= ?)
                   AND ci.item_code = 'BASE_COMMISSION'
                """, Date.valueOf(windowStart), Date.valueOf(windowEnd));
        if (caps.size() != 1) {
            throw new IllegalStateException("계약 기간 전체를 덮는 원수사→GA 1,200% 룰셋이 1건으로 정해지지 않는다: " + caps.size()
                    + "건 (V58 적용 여부를 확인한다)");
        }
        // 월 통합검증은 상품·납입기간·채널로 예상 해약환급률표를 찾는다. 표가 없는 납입기간의 계약은 REVIEW_REQUIRED가 되므로
        // 계약 납입기간은 보험사의 현행 판매버전 상품에 연결된 활성 환급률표의 값을 그대로 쓴다.
        Map<String, Integer> terms = new HashMap<>();
        for (String insurerCode : Dar012Planner.INSURER_CODES) {
            List<Integer> found = jdbc.queryForList("""
                    SELECT DISTINCT rt.payment_term_months
                      FROM refund_rate_table rt
                      JOIN policy_version pv ON pv.policy_version_id = rt.policy_version_id AND pv.status = 'ACTIVE'
                      JOIN product p ON p.product_id = rt.product_id
                      JOIN insurer i ON i.insurer_id = p.insurer_id
                      JOIN product_offering po ON po.product_id = p.product_id
                     WHERE i.insurer_code = ? AND rt.channel_code = po.channel_code AND po.fee_regime_code = 'CURRENT'
                       AND po.active_yn AND po.sales_start_date <= ? AND (po.sales_end_date IS NULL OR po.sales_end_date >= ?)
                    """, Integer.class, insurerCode, Date.valueOf(windowStart), Date.valueOf(windowEnd));
            if (found.size() != 1) {
                throw new IllegalStateException("현행 판매버전 상품의 예상 해약환급률표 납입기간이 1개로 정해지지 않는 보험사: "
                        + insurerCode + " (" + found.size() + "개)");
            }
            terms.put(insurerCode, found.get(0));
        }
        return new PolicyRefs(new Dar012Planner.PolicyInputs((BigDecimal) rates.get(0).get("rate_pct"),
                (BigDecimal) caps.get(0).get("premium_multiplier"), Map.copyOf(terms)),
                ((Number) rates.get(0).get("policy_version_id")).longValue(),
                ((Number) caps.get(0).get("cap_rule_item_id")).longValue());
    }

    /** 같은 DB에 DAR-012 데이터가 이미 있으면 멈춘다. 설계사 코드가 데이터셋 접두어를 공유하므로 dataset_id가 달라도 막는다. */
    public void assertNotLoaded(String datasetId) {
        Integer agents = jdbc.queryForObject("SELECT COUNT(*) FROM agent WHERE starts_with(agent_code, 'D12-A-')", Integer.class);
        Integer batches = jdbc.queryForObject("SELECT COUNT(*) FROM statement_batch WHERE starts_with(source_ref, ?)",
                Integer.class, SOURCE_PREFIX + datasetId + ":");
        if ((agents != null && agents > 0) || (batches != null && batches > 0)) {
            throw new IllegalStateException("이 DB에는 이미 DAR-012 데이터셋이 있다(dataset_id=" + datasetId
                    + "). 새 DB에 Flyway를 적용한 뒤 다시 실행한다 — DB 안에서 행을 지워 초기화하지 않는다(시드명세 §43)");
        }
    }

    public LoadResult load(Dar012Plan plan, PolicyRefs policy) {
        return tx.execute(status -> {
            assertNotLoaded(plan.datasetId());
            String source = SOURCE_PREFIX + plan.datasetId();
            Map<String, Long> insurer = idMap("SELECT insurer_code, insurer_id FROM insurer");
            Map<String, Long> offering = offeringByInsurer(plan);
            long itemBase = one("SELECT commission_item_id FROM commission_item WHERE item_code = 'BASE_COMMISSION'");
            long creator = one("SELECT user_id FROM app_user WHERE login_id = 'settle01'");

            for (OrgRow o : plan.organizations()) {
                jdbc.update("""
                        INSERT INTO organization(parent_id, organization_code, organization_name, organization_type, effective_from)
                        VALUES ((SELECT organization_id FROM organization WHERE organization_code = ?), ?, ?, ?, ?)
                        """, o.parentCode(), o.code(), o.name(), o.type(), Date.valueOf(ORG_EFFECTIVE_FROM));
            }
            Map<String, Long> org = idMap("SELECT organization_code, organization_id FROM organization WHERE starts_with(organization_code, 'D12-')");

            batch("""
                    INSERT INTO agent(organization_id, agent_code, agent_name, rank_code, appointment_date, termination_date,
                                      agent_status, latest_registration_date, active_yn)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, plan.agents(), (ps, a) -> {
                ps.setLong(1, org.get(a.orgCode()));
                ps.setString(2, a.code());
                ps.setString(3, a.name());
                ps.setString(4, a.rankCode());
                ps.setDate(5, Date.valueOf(a.appointmentDate()));
                ps.setDate(6, a.terminationDate() == null ? null : Date.valueOf(a.terminationDate()));
                ps.setString(7, a.status());
                ps.setDate(8, Date.valueOf(a.appointmentDate()));
                ps.setBoolean(9, "ACTIVE".equals(a.status()));
            });
            Map<String, Long> agent = idMap("SELECT agent_code, agent_id FROM agent WHERE starts_with(agent_code, 'D12-A-')");

            batch("""
                    INSERT INTO agent_insurer_code(insurer_id, agent_id, insurer_agent_code, effective_from, source_ref)
                    VALUES (?, ?, ?, ?, ?)
                    """, plan.agentCodes(), (ps, c) -> {
                ps.setLong(1, insurer.get(c.insurerCode()));
                ps.setLong(2, agent.get(c.agentCode()));
                ps.setString(3, c.insurerAgentCode());
                ps.setDate(4, Date.valueOf(ORG_EFFECTIVE_FROM));
                ps.setString(5, source + ":AGENT-CODE");
            });

            batch("""
                    INSERT INTO insurance_contract(insurer_id, product_offering_id, contract_no, contract_date, agent_id,
                        organization_id, premium_per_cycle_amount, first_premium_amount, monthly_equivalent_first_premium,
                        premium_conversion_rule_code, payment_cycle_code, payment_term_months, current_status, data_origin,
                        created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SEED', ?)
                    """, plan.contracts(), (ps, c) -> {
                ps.setLong(1, insurer.get(c.insurerCode()));
                ps.setLong(2, offering.get(c.insurerCode()));
                ps.setString(3, c.contractNo());
                ps.setDate(4, Date.valueOf(c.contractDate()));
                ps.setLong(5, agent.get(c.agentCode()));
                ps.setLong(6, org.get(c.orgCode()));
                ps.setBigDecimal(7, c.premiumPerCycle());
                ps.setBigDecimal(8, c.premiumPerCycle());
                ps.setBigDecimal(9, c.monthlyEquivalent());
                ps.setString(10, c.conversionRuleCode());
                ps.setString(11, c.paymentCycleCode());
                ps.setInt(12, c.paymentTermMonths());
                ps.setString(13, c.currentStatus());
                ps.setLong(14, creator);
            });
            Map<String, Long> contract = idMap("""
                    SELECT contract_no, contract_id FROM insurance_contract
                     WHERE agent_id IN (SELECT agent_id FROM agent WHERE starts_with(agent_code, 'D12-A-'))
                    """);

            batch("""
                    INSERT INTO contract_status_event(contract_id, event_seq, previous_status, new_status, effective_at,
                        received_at, reason_code, source_system, source_event_key, data_origin, source_ref)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'SYNTHETIC-DAR012', ?, 'SEED', ?)
                    """, plan.events(), (ps, e) -> {
                ps.setLong(1, contract.get(e.contractNo()));
                ps.setInt(2, e.seq());
                ps.setString(3, e.previousStatus());
                ps.setString(4, e.newStatus());
                ps.setTimestamp(5, Timestamp.from(e.effectiveAt().toInstant()));
                ps.setTimestamp(6, Timestamp.from(e.receivedAt().toInstant()));
                ps.setString(7, e.reasonCode());
                ps.setString(8, plan.datasetId() + ":" + e.contractNo() + ":" + e.seq());
                ps.setString(9, source + ":EVENT");
            });

            batch("""
                    INSERT INTO contract_financial_snapshot(contract_id, as_of_date, contract_month_no, cumulative_paid_premium,
                        surrender_value, surrender_value_type, source_ref)
                    VALUES (?, ?, 1, ?, NULL, 'EXPECTED_TABLE', ?)
                    """, plan.snapshots(), (ps, s) -> {
                ps.setLong(1, contract.get(s.contractNo()));
                ps.setDate(2, Date.valueOf(s.asOfDate()));
                ps.setBigDecimal(3, s.cumulativePaidPremium());
                ps.setString(4, source + ":SNAPSHOT");
            });

            batch("""
                    INSERT INTO statement_batch(insurer_id, settlement_month, statement_type, external_statement_no,
                        received_on, source_ref, data_origin)
                    VALUES (?, ?, 'INSURER_COMMISSION', ?, ?, ?, 'SEED')
                    """, plan.batches(), (ps, b) -> {
                ps.setLong(1, insurer.get(b.insurerCode()));
                ps.setDate(2, Date.valueOf(b.settlementMonth()));
                ps.setString(3, b.externalStatementNo());
                ps.setDate(4, Date.valueOf(b.receivedOn()));
                ps.setString(5, b.sourceRef());
            });
            Map<String, Long> statementBatch = new HashMap<>();
            jdbc.query("SELECT insurer_id, settlement_month, statement_batch_id FROM statement_batch WHERE starts_with(source_ref, ?)",
                    rs -> {
                        statementBatch.put(rs.getLong(1) + "|" + rs.getDate(2).toLocalDate(), rs.getLong(3));
                    }, source + ":");

            batch("""
                    INSERT INTO commission_transaction(statement_batch_id, payment_stage, source_type, source_business_key,
                        insurer_id, commission_item_id, policy_version_id, settlement_month, due_date, paid_on, amount,
                        cashflow_type, status, evidence_ref, created_by, installment_no)
                    VALUES (?, 'INSURER_TO_GA', 'INSURER_STATEMENT', ?, ?, ?, ?, ?, ?, ?, ?, 'PAYMENT', 'DRAFT', ?, ?, ?)
                    """, plan.payments(), (ps, p) -> {
                long insurerId = insurer.get(p.insurerCode());
                ps.setLong(1, statementBatch.get(insurerId + "|" + p.settlementMonth()));
                ps.setString(2, p.businessKey());
                ps.setLong(3, insurerId);
                ps.setLong(4, itemBase);
                ps.setLong(5, policy.policyVersionId());
                ps.setDate(6, Date.valueOf(p.settlementMonth()));
                ps.setDate(7, Date.valueOf(p.dueDate()));
                ps.setDate(8, p.paidOn() == null ? null : Date.valueOf(p.paidOn()));
                ps.setBigDecimal(9, p.amount());
                ps.setString(10, source);
                ps.setLong(11, creator);
                ps.setInt(12, p.installmentNo());
            });
            Map<String, Long> payment = idMap("SELECT source_business_key, commission_transaction_id FROM commission_transaction "
                    + "WHERE starts_with(source_business_key, ?)", source + ":");

            Map<String, ContractRow> contractByNo = new HashMap<>();
            plan.contracts().forEach(c -> contractByNo.put(c.contractNo(), c));
            batch("""
                    INSERT INTO transaction_attribution(commission_transaction_id, attribution_seq, attribution_scope,
                        contract_id, agent_id, source_agent_code, attribution_date, attribution_month, attributed_amount,
                        cap_rule_item_id, inclusion_status_snapshot, attribution_method)
                    VALUES (?, 1, 'CONTRACT', ?, ?, ?, ?, ?, ?, ?, 'INCLUDED', 'DIRECT')
                    """, plan.payments(), (ps, p) -> {
                ContractRow c = contractByNo.get(p.contractNo());
                ps.setLong(1, payment.get(p.businessKey()));
                ps.setLong(2, contract.get(p.contractNo()));
                ps.setLong(3, agent.get(c.agentCode()));
                ps.setString(4, p.insurerAgentCode());
                ps.setDate(5, Date.valueOf(p.settlementMonth().plusMonths(1).minusDays(1)));
                ps.setDate(6, Date.valueOf(p.settlementMonth()));
                ps.setBigDecimal(7, p.amount());
                ps.setLong(8, policy.capRuleItemId());
            });

            // 귀속합계가 지급액과 같을 때만 DB 가드가 CONFIRMED를 허용한다(§22).
            int confirmed = jdbc.update("UPDATE commission_transaction SET status = 'CONFIRMED' "
                    + "WHERE status = 'DRAFT' AND starts_with(source_business_key, ?)", source + ":");

            boolean linked = linkAgent01(agent.get("D12-A-0001"));
            log.info("DAR-012 적재 완료: dataset_id={}, 설계사={}, 계약={}, 지급={}, agent01 연결={}", plan.datasetId(),
                    plan.agents().size(), plan.contracts().size(), confirmed, linked);
            return new LoadResult(confirmed, linked);
        });
    }

    /** 대표 설계사 연결(시드명세 §5). AGENT 계정이 아직 없으면(FUN-085 이전) 건너뛰고 결과에 남긴다. */
    private boolean linkAgent01(Long representativeAgentId) {
        return jdbc.update("UPDATE app_user SET agent_id = ? WHERE login_id = 'agent01'", representativeAgentId) > 0;
    }

    public record LoadResult(int confirmedPayments, boolean agent01Linked) {}

    private Map<String, Long> offeringByInsurer(Dar012Plan plan) {
        LocalDate first = plan.contracts().stream().map(ContractRow::contractDate).min(LocalDate::compareTo).orElseThrow();
        LocalDate last = plan.contracts().stream().map(ContractRow::contractDate).max(LocalDate::compareTo).orElseThrow();
        Map<String, Long> result = new HashMap<>();
        for (String insurerCode : Dar012Planner.INSURER_CODES) {
            List<Long> ids = jdbc.queryForList("""
                    SELECT po.product_offering_id
                      FROM product_offering po
                      JOIN product p ON p.product_id = po.product_id
                      JOIN insurer i ON i.insurer_id = p.insurer_id
                     WHERE i.insurer_code = ? AND po.active_yn AND po.fee_regime_code = 'CURRENT'
                       AND po.sales_start_date <= ? AND (po.sales_end_date IS NULL OR po.sales_end_date >= ?)
                     ORDER BY po.sales_start_date DESC, po.product_offering_id
                     LIMIT 1
                    """, Long.class, insurerCode, Date.valueOf(first), Date.valueOf(last));
            if (ids.isEmpty()) {
                throw new IllegalStateException("계약일에 유효한 현행 판매버전이 없는 보험사: " + insurerCode);
            }
            result.put(insurerCode, ids.get(0));
        }
        return result;
    }

    private long one(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        if (value == null) {
            throw new IllegalStateException("필수 기준정보를 찾지 못했다: " + sql);
        }
        return value;
    }

    private Map<String, Long> idMap(String sql, Object... args) {
        Map<String, Long> map = new HashMap<>();
        jdbc.query(sql, rs -> {
            map.put(rs.getString(1), rs.getLong(2));
        }, args);
        return map;
    }

    private <T> void batch(String sql, List<T> rows, org.springframework.jdbc.core.ParameterizedPreparedStatementSetter<T> setter) {
        jdbc.batchUpdate(sql, rows, BATCH_SIZE, setter);
    }
}
