package com.susukkang.fgc.dataset;

import com.susukkang.fgc.dataset.Dar012Plan.CapBoundaryRow;
import com.susukkang.fgc.dataset.Dar012Plan.ContractRow;
import com.susukkang.fgc.dataset.Dar012Plan.GaPaymentRow;
import com.susukkang.fgc.dataset.Dar012Plan.OrgRow;
import com.susukkang.fgc.dataset.Dar012Plan.PaymentRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

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
    /** 새 DB(db/migration + db/demo)를 실제로 조회해 고정한 기본 계약 목록. db/demo에 계약을 더하면 이 목록도 고친다. */
    private static final Set<String> BASE_CONTRACT_NOS = Set.of(
            "FGC-FGL01-202601-0001", "FGC-FGL01-202603-0475", "FGC-FGL01-202607-0001", "FGC-FGL01-202607-0002",
            "FGC-FGL01-202607-0003", "FGC-FGL01-202607-0004", "FGC-FGL01-202607-0005", "FGC-FGL01-202608-0001",
            "FGC-FGL01-202608-0002", "FGC-FGL01-202608-0003", "FGC-FGL01-202608-0004", "FGC-FGL01-202608-0005",
            "FGC-FGL01-202703-0001", "FGC-FGL02-202601-0001", "FGC-FGL02-202603-0475", "FGC-FGL02-202605-0001",
            "FGC-FGL02-202608-0001", "FGC-FGL02-202611-0001", "FGC-FGL03-202608-0001", "FGC-FGN01-202603-0001",
            "FGC-FGN01-202605-0001");
    /** V64가 만든 agent01의 초기 연결(시드명세 §5). */
    private static final String BASE_AGENT01_AGENT_CODE = "A-FC-001";
    private static final String AGENT01_AGENT_CODE_SQL =
            "SELECT a.agent_code FROM app_user u JOIN agent a ON a.agent_id = u.agent_id WHERE u.login_id = 'agent01'";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public Dar012Loader(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** 계획에 쓸 정책값. 승인된 ACTIVE 정책에서만 읽고 하나로 정해지지 않으면 중단한다. */
    public record PolicyRefs(Dar012Planner.PolicyInputs inputs, long policyVersionId, long capRuleItemId,
                             long gaPolicyVersionId, long gaCapRuleItemId) {}

    public PolicyRefs readPolicy(LocalDate windowStart, LocalDate windowEnd) {
        Map<String, Object> insurerRate = baseRate("INSURER_TO_GA", null, windowStart, windowEnd);
        Map<String, Object> gaRate = baseRate("GA_TO_FC", "FC", windowStart, windowEnd);
        Map<String, Object> insurerCap = baseCapRule("INSURER_TO_GA", windowStart, windowEnd);
        Map<String, Object> gaCap = baseCapRule("GA_TO_FC", windowStart, windowEnd);
        long policyVersionId = ((Number) insurerRate.get("policy_version_id")).longValue();
        int firstYearMonths = ((Number) insurerCap.get("first_year_months")).intValue();
        List<Dar012Planner.FirstYearLine> firstYearLines = jdbc.query("""
                SELECT r.rate_pct, LEAST(r.installment_to, ?) - r.installment_from + 1 AS installments
                  FROM commission_rule r
                 WHERE r.policy_version_id = ? AND r.payment_stage = 'INSURER_TO_GA' AND r.calculation_type = 'RATE'
                   AND r.insurer_id IS NULL AND r.product_offering_id IS NULL AND r.installment_from <= ?
                 ORDER BY r.installment_from, r.commission_rule_id
                """, (rs, row) -> new Dar012Planner.FirstYearLine(rs.getBigDecimal(1), rs.getInt(2)),
                firstYearMonths, policyVersionId, firstYearMonths);
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
        return new PolicyRefs(new Dar012Planner.PolicyInputs((BigDecimal) insurerRate.get("rate_pct"),
                (BigDecimal) insurerCap.get("premium_multiplier"), Map.copyOf(terms), (BigDecimal) gaRate.get("rate_pct"),
                List.copyOf(firstYearLines)),
                policyVersionId,
                ((Number) insurerCap.get("cap_rule_item_id")).longValue(),
                ((Number) gaRate.get("policy_version_id")).longValue(),
                ((Number) gaCap.get("cap_rule_item_id")).longValue());
    }

    private Map<String, Object> baseRate(String stage, String rankCode, LocalDate windowStart, LocalDate windowEnd) {
        List<Map<String, Object>> rates = jdbc.queryForList("""
                SELECT r.rate_pct, pv.policy_version_id
                  FROM commission_rule r
                  JOIN policy_version pv ON pv.policy_version_id = r.policy_version_id
                  JOIN commission_item ci ON ci.commission_item_id = r.commission_item_id
                 WHERE pv.policy_type = 'CURRENT_COMMISSION' AND pv.status = 'ACTIVE'
                   AND pv.effective_from <= ? AND (pv.effective_to IS NULL OR pv.effective_to >= ?)
                   AND r.payment_stage = ? AND r.agent_rank_code IS NOT DISTINCT FROM CAST(? AS varchar)
                   AND ci.item_code = 'BASE_COMMISSION'
                   AND r.installment_from = 1 AND r.calculation_type = 'RATE'
                   AND r.insurer_id IS NULL AND r.product_offering_id IS NULL
                """, Date.valueOf(windowStart), Date.valueOf(windowEnd), stage, rankCode);
        if (rates.size() != 1) {
            throw new IllegalStateException(stage + " 초회 기본수수료율 정책이 1건으로 정해지지 않는다: " + rates.size() + "건");
        }
        return rates.get(0);
    }

    private Map<String, Object> baseCapRule(String stage, LocalDate windowStart, LocalDate windowEnd) {
        List<Map<String, Object>> caps = jdbc.queryForList("""
                SELECT crs.premium_multiplier, crs.first_year_months, cri.cap_rule_item_id
                  FROM cap_rule_set crs
                  JOIN policy_version pv ON pv.policy_version_id = crs.policy_version_id
                  JOIN cap_rule_item cri ON cri.cap_rule_set_id = crs.cap_rule_set_id
                  JOIN commission_item ci ON ci.commission_item_id = cri.commission_item_id
                 WHERE pv.status = 'ACTIVE' AND crs.payment_stage = ?
                   AND crs.insurer_id IS NULL AND crs.product_group_code IS NULL AND crs.channel_code IS NULL
                   AND crs.contract_date_from <= ? AND (crs.contract_date_to IS NULL OR crs.contract_date_to >= ?)
                   AND ci.item_code = 'BASE_COMMISSION'
                """, stage, Date.valueOf(windowStart), Date.valueOf(windowEnd));
        if (caps.size() != 1) {
            throw new IllegalStateException("계약 기간 전체를 덮는 " + stage + " 1,200% 룰셋이 1건으로 정해지지 않는다: " + caps.size()
                    + "건 (db/demo V63 적용 여부를 확인한다)");
        }
        return caps.get(0);
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

    /**
     * 기본 시드 그대로인 새 격리 DB에만 적재한다(시드명세 §50). 업무나 배치를 돌린 DB, 목록과 다른 계약이 있는 DB,
     * agent01 연결이 바뀐 DB는 아무것도 쓰기 전에 멈춘다. 적재한 행은 지울 수 없어서 사용 중인 DB에 넣으면 되돌릴 방법이 없다.
     */
    public void assertFreshBaseline() {
        List<String> problems = new ArrayList<>();
        jdbc.queryForMap("""
                SELECT (SELECT COUNT(*) FROM validation_run) AS validation_run,
                       (SELECT COUNT(*) FROM schedule_header) AS schedule_header,
                       (SELECT COUNT(*) FROM cap_check) AS cap_check,
                       (SELECT COUNT(*) FROM journal_header) AS journal_header,
                       (SELECT COUNT(*) FROM exception_case) AS exception_case,
                       (SELECT COUNT(*) FROM reconciliation_run) AS reconciliation_run,
                       (SELECT COUNT(*) FROM audit_log) AS audit_log
                """).forEach((table, rows) -> {
            if (((Number) rows).longValue() > 0) {
                problems.add(table + " " + rows + "건");
            }
        });
        Set<String> contracts = new TreeSet<>(jdbc.queryForList("SELECT contract_no FROM insurance_contract", String.class));
        Set<String> missing = new TreeSet<>(BASE_CONTRACT_NOS);
        missing.removeAll(contracts);
        contracts.removeAll(BASE_CONTRACT_NOS);
        if (!contracts.isEmpty()) {
            problems.add("목록에 없는 계약 " + contracts.size() + "건(첫 번호 " + contracts.iterator().next() + ")");
        }
        if (!missing.isEmpty()) {
            problems.add("목록에서 빠진 계약 " + missing);
        }
        List<String> agent01 = jdbc.queryForList(AGENT01_AGENT_CODE_SQL, String.class);
        if (!List.of(BASE_AGENT01_AGENT_CODE).equals(agent01)) {
            problems.add("agent01 연결 " + agent01 + "(기본 시드는 " + BASE_AGENT01_AGENT_CODE + ")");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("기본 시드 그대로인 새 DB가 아니라 적재하지 않는다: " + String.join(", ", problems)
                    + ". 새 격리 DB에 Flyway를 적용한 뒤 실행한다. 신한 서버와 개발계 DB에는 적재하지 않는다(시드명세 §50)");
        }
    }

    public LoadResult load(Dar012Plan plan, PolicyRefs policy) {
        return tx.execute(status -> {
            assertNotLoaded(plan.datasetId());
            assertFreshBaseline();
            String source = SOURCE_PREFIX + plan.datasetId();
            Map<String, Long> insurer = idMap("SELECT insurer_code, insurer_id FROM insurer");
            Map<String, Long> offering = offeringByInsurer(plan);
            long itemBase = one("SELECT commission_item_id FROM commission_item WHERE item_code = 'BASE_COMMISSION'");
            long creator = one("SELECT user_id FROM app_user WHERE login_id = 'settle01'");
            // 판매버전 조회(offeringByInsurer) 뒤에 만들어야 전용 상품이 일반 계약의 판매버전으로 뽑히지 않는다.
            long capBoundaryOffering = insertCapBoundaryProduct(plan, insurer.get("FGL01"), source, creator);
            Set<String> capBoundaryContracts = plan.capBoundaries().stream().map(CapBoundaryRow::contractNo).collect(Collectors.toSet());

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
                ps.setLong(2, capBoundaryContracts.contains(c.contractNo()) ? capBoundaryOffering : offering.get(c.insurerCode()));
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
            batch("""
                    INSERT INTO commission_transaction(payment_stage, source_type, source_business_key, recipient_agent_id,
                        commission_item_id, policy_version_id, settlement_month, due_date, paid_on, amount,
                        cashflow_type, status, evidence_ref, created_by, installment_no)
                    VALUES ('GA_TO_FC', 'GA_CONFIRMED_PAYMENT', ?, ?, ?, ?, ?, ?, ?, ?, 'PAYMENT', 'DRAFT', ?, ?, ?)
                    """, plan.gaPayments(), (ps, p) -> {
                ps.setString(1, p.businessKey());
                ps.setLong(2, agent.get(p.agentCode()));
                ps.setLong(3, itemBase);
                ps.setLong(4, policy.gaPolicyVersionId());
                ps.setDate(5, Date.valueOf(p.settlementMonth()));
                ps.setDate(6, Date.valueOf(p.dueDate()));
                ps.setDate(7, Date.valueOf(p.paidOn()));
                ps.setBigDecimal(8, p.amount());
                ps.setString(9, source);
                ps.setLong(10, creator);
                ps.setInt(11, p.installmentNo());
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

            batch("""
                    INSERT INTO transaction_attribution(commission_transaction_id, attribution_seq, attribution_scope,
                        contract_id, agent_id, attribution_date, attribution_month, attributed_amount,
                        cap_rule_item_id, inclusion_status_snapshot, attribution_method)
                    VALUES (?, 1, 'CONTRACT', ?, ?, ?, ?, ?, ?, 'INCLUDED', 'DIRECT')
                    """, plan.gaPayments(), (ps, p) -> {
                ps.setLong(1, payment.get(p.businessKey()));
                ps.setLong(2, contract.get(p.contractNo()));
                ps.setLong(3, agent.get(p.agentCode()));
                ps.setDate(4, Date.valueOf(p.paidOn()));
                ps.setDate(5, Date.valueOf(p.settlementMonth()));
                ps.setBigDecimal(6, p.amount());
                ps.setLong(7, policy.gaCapRuleItemId());
            });

            // 귀속합계가 지급액과 같을 때만 DB 가드가 CONFIRMED를 허용한다(§22).
            int confirmed = jdbc.update("UPDATE commission_transaction SET status = 'CONFIRMED' "
                    + "WHERE status = 'DRAFT' AND starts_with(source_business_key, ?)", source + ":");

            String previousAgentCode = linkAgent01(agent.get("D12-A-0001"));
            log.info("DAR-012 적재 완료: dataset_id={}, 설계사={}, 계약={}, 지급={}, agent01 이전 연결={}", plan.datasetId(),
                    plan.agents().size(), plan.contracts().size(), confirmed, previousAgentCode);
            return new LoadResult(confirmed, previousAgentCode);
        });
    }

    /** 대표 설계사 연결(시드명세 §5). 게이트가 초기 연결을 확인한 같은 트랜잭션에서 옮기고 이전 연결을 돌려준다. */
    private String linkAgent01(Long representativeAgentId) {
        String previousAgentCode = jdbc.queryForObject(AGENT01_AGENT_CODE_SQL, String.class);
        jdbc.update("UPDATE app_user SET agent_id = ? WHERE login_id = 'agent01'", representativeAgentId);
        return previousAgentCode;
    }

    public record LoadResult(int confirmedPayments, String agent01PreviousAgentCode) {}

    /**
     * 한도 경계 계약 전용 상품, 판매버전, 환급률표. 다른 계약과 공유하지 않아 일반 데이터의 판정을 흔들지 않는다.
     * 환급률표는 정책 승인 절차를 따른다(시드명세 §4-1): DRAFT에서 자식 행을 넣고 APPROVED를 거쳐 ACTIVE로 올린다.
     */
    private long insertCapBoundaryProduct(Dar012Plan plan, long insurerId, String source, long creator) {
        long approver = one("SELECT user_id FROM app_user WHERE login_id = 'gaadmin'");
        Long policyVersionId = jdbc.queryForObject("""
                INSERT INTO policy_version(policy_code, policy_name, policy_type, source_class, version_no, effective_from,
                                           status, regulation_refs, source_refs, approval_evidence_ref, created_by)
                VALUES ('INS-REFUND-D12-CAP-V1', '예상 해약환급률표(DAR-012 한도 경계)', 'REFUND_RATE', 'INSURER_RULE', 1,
                        DATE '2026-01-01', 'DRAFT', ARRAY['REG-08'], ARRAY['DAR-012 합성값'], 'PROJECT_ASSUMPTION 합성값', ?)
                RETURNING policy_version_id
                """, Long.class, creator);
        jdbc.update("""
                INSERT INTO policy_parameter(policy_version_id, parameter_key, parameter_value, description)
                VALUES (?, 'REFUND_RATE_BASIS', '"MONTHLY_EQUIVALENT_FIRST_PREMIUM_X12"'::jsonb,
                        '환급률(%)을 곱할 기준 금액. 월납환산 초회보험료 x 12')
                """, policyVersionId);
        Long productId = jdbc.queryForObject("""
                INSERT INTO product(insurer_id, insurer_product_code, standard_product_code, product_name, product_group_code, protection_type)
                VALUES (?, 'P-D12-CAP', 'STD-D12-CAP', '합성 한도 경계 건강보험', 'HEALTH_PROTECTION', 'PROTECTION')
                RETURNING product_id
                """, Long.class, insurerId);
        Long offeringId = jdbc.queryForObject("""
                INSERT INTO product_offering(product_id, offering_version, sales_start_date, basic_document_version, basic_document_date,
                                             channel_code, fee_regime_code, standard_deduction_80_yn, committee_approval_date, governance_status)
                VALUES (?, 'D12-CAP-A', DATE '2026-01-01', 'BD-2026-01', DATE '2026-01-01',
                        'FACE_TO_FACE', 'CURRENT', true, DATE '2026-01-01', 'APPROVED')
                RETURNING product_offering_id
                """, Long.class, productId);
        for (CapBoundaryRow boundary : plan.capBoundaries()) {
            Long tableId = jdbc.queryForObject("""
                    INSERT INTO refund_rate_table(policy_version_id, insurer_id, product_id, product_offering_id, payment_term_months,
                                                  channel_code, standard_deduction_80_yn, effective_from, source_product_code, source_document_ref)
                    VALUES (?, ?, ?, ?, ?, 'FACE_TO_FACE', true, DATE '2026-01-01', 'STD-D12-CAP', ?)
                    RETURNING refund_rate_table_id
                    """, Long.class, policyVersionId, insurerId, productId, offeringId, boundary.paymentTermMonths(), source);
            jdbc.update("""
                    INSERT INTO refund_rate_line(refund_rate_table_id, contract_month_no, refund_rate_pct)
                    SELECT ?, m, ROUND(? * m / 12, 6) FROM generate_series(1, 36) AS m
                    """, tableId, boundary.month12RefundRatePct());
        }
        jdbc.update("""
                UPDATE policy_version SET status = 'APPROVED', approved_at = TIMESTAMPTZ '2026-06-30 09:00:00+09', approved_by = ?
                 WHERE policy_version_id = ?
                """, approver, policyVersionId);
        jdbc.update("UPDATE policy_version SET status = 'ACTIVE' WHERE policy_version_id = ?", policyVersionId);
        return offeringId;
    }

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
