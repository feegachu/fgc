package com.susukkang.fgc.dashboard;

import com.susukkang.fgc.base.dto.AgentSearchCriteria;
import com.susukkang.fgc.base.service.AgentService;
import com.susukkang.fgc.dashboard.dto.HomeSummaryResponse;
import com.susukkang.fgc.dashboard.dto.HomeSummaryResponse.HomeBranch;
import com.susukkang.fgc.dashboard.service.HomeSummaryService;
import com.susukkang.fgc.exceptioncase.service.ExceptionCaseService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FUN-080 인수조건의 규모(DAR-012: 지점 12·설계사 600명·계약 1만 2천 건)에서 IF-API-68·69 응답 시간과 집계를 본다.
 * 기초 행은 트랜잭션 안에서 넣고 되돌린다. 실제 DAR-012 데이터셋의 p95 측정은 부하 시험(k6)의 몫이고, 여기서는 쿼리 구조가 PER-004(3초)를 넘지 않는지 지킨다.
 */
@SpringBootTest(properties = "fgc.batch.daily-changed-contract.enabled=false")
@Transactional
class HomeSummaryPerformanceTest {

    private static final Logger log = LoggerFactory.getLogger(HomeSummaryPerformanceTest.class);
    private static final LocalDate AS_OF = LocalDate.of(1997, 6, 30);
    private static final int AGENTS = 600;
    private static final int CONTRACTS = 12_000;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private HomeSummaryService home;
    @Autowired private ExceptionCaseService exceptions;
    @Autowired private AgentService agents;

    @Test
    void 설계사_600명_계약_1만2천건에서_3초를_넘지_않고_소계가_전체와_맞는다() {
        seed();

        List<Long> samples = new ArrayList<>();
        HomeSummaryResponse summary = null;
        for (int run = 0; run < 7; run++) {
            long start = System.nanoTime();
            summary = home.summarize(AS_OF);
            samples.add(Duration.ofNanos(System.nanoTime() - start).toMillis());
        }
        samples.sort(Comparator.naturalOrder());
        long start = System.nanoTime();
        exceptions.counts("OPEN", null);
        long countsMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
        log.info("IF-API-68 ms samples={}, IF-API-69 ms={}", samples, countsMillis);

        // 가장 느린 값으로 잰다. 시험 기계가 느려도 넘지 말아야 할 한도다.
        assertThat(samples.get(samples.size() - 1)).isLessThan(3_000L);
        assertThat(countsMillis).isLessThan(3_000L);

        // 1997년 기준일에는 시드 조직과 설계사가 들어오지 않아 지점은 12곳이다.
        assertThat(summary.agents().registered()).isEqualTo(560);
        assertThat(summary.agents().active()).isEqualTo(540);
        assertThat(summary.agents().terminated()).isEqualTo(40);
        assertThat(summary.branches()).hasSize(12);
        assertThat(summary.branches().stream().mapToLong(HomeBranch::agentCount).sum()).isEqualTo(560);
        assertThat(summary.branches().stream().mapToLong(HomeBranch::activeContractCount).sum()).isEqualTo(CONTRACTS);
        assertThat(summary.attentionAgents()).hasSize(10);

        // 600명 모두 검색으로 찾을 수 있다.
        assertThat(agents.search(new AgentSearchCriteria(null, "IT-P-", AS_OF, true), 1, 10).totalElements())
                .isEqualTo(AGENTS);
    }

    private void seed() {
        jdbc.update("""
                INSERT INTO fgc.organization(organization_code, organization_name, organization_type, effective_from)
                SELECT 'IT-P-B' || lpad(b::text, 2, '0'), '성능지점 ' || b, 'BRANCH', DATE '1997-01-01'
                  FROM generate_series(1, 12) b
                """);
        jdbc.update("""
                INSERT INTO fgc.organization(organization_code, organization_name, organization_type, parent_id, effective_from)
                SELECT o.organization_code || '-T' || t, o.organization_name || ' ' || t || '팀', 'TEAM', o.organization_id, DATE '1997-01-01'
                  FROM fgc.organization o CROSS JOIN generate_series(1, 2) t
                 WHERE o.organization_code LIKE 'IT-P-B%'
                """);
        jdbc.update("""
                INSERT INTO fgc.agent(organization_id, agent_code, agent_name, rank_code, appointment_date, termination_date, agent_status)
                SELECT (SELECT organization_id FROM fgc.organization
                         WHERE organization_code = 'IT-P-B' || lpad((n % 12 + 1)::text, 2, '0') || '-T' || (n % 2 + 1)),
                       'IT-P-' || lpad(n::text, 4, '0'), '성능' || n, 'FC', DATE '1997-01-01',
                       CASE WHEN n > 560 THEN DATE '1997-03-01' END,
                       CASE WHEN n <= 540 THEN 'ACTIVE' WHEN n <= 560 THEN 'INACTIVE' ELSE 'TERMINATED' END
                  FROM generate_series(1, ?) n
                """, AGENTS);
        jdbc.update("""
                INSERT INTO fgc.insurance_contract (
                    insurer_id, product_offering_id, contract_no, contract_date, agent_id, organization_id,
                    premium_per_cycle_amount, first_premium_amount, monthly_equivalent_first_premium,
                    premium_conversion_rule_code, payment_cycle_code, payment_term_months, current_status, data_origin)
                SELECT t.insurer_id, t.product_offering_id, 'IT-P-C' || n, DATE '1997-02-01', a.agent_id, a.organization_id,
                       t.premium_per_cycle_amount, t.first_premium_amount, t.monthly_equivalent_first_premium,
                       t.premium_conversion_rule_code, t.payment_cycle_code, t.payment_term_months, 'ACTIVE', 'MANUAL'
                  FROM generate_series(1, ?) n
                  JOIN fgc.agent a ON a.agent_code = 'IT-P-' || lpad((n % 540 + 1)::text, 4, '0')
                 CROSS JOIN (SELECT * FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1) t
                """, CONTRACTS);
        // 계약마다 상태 사건 3건: 청약, 성립, 기준일 뒤 해지. 지난 기준일 계산이 사건을 모두 뒤진다.
        jdbc.update("""
                INSERT INTO fgc.contract_status_event(contract_id, event_seq, new_status, effective_at, received_at,
                    reason_code, source_system, source_event_key)
                SELECT c.contract_id, s, CASE s WHEN 1 THEN 'APPLIED' WHEN 2 THEN 'ACTIVE' ELSE 'TERMINATED' END,
                       e.at, e.at, 'TEST', 'IT-PERF', 'IT-P:' || c.contract_id || ':' || s
                  FROM fgc.insurance_contract c
                 CROSS JOIN generate_series(1, 3) s
                 CROSS JOIN LATERAL (SELECT CASE s WHEN 1 THEN TIMESTAMPTZ '1997-02-01 09:00+09'
                                                   WHEN 2 THEN TIMESTAMPTZ '1997-02-02 09:00+09'
                                                   ELSE TIMESTAMPTZ '1997-08-01 09:00+09' END AS at) e
                 WHERE c.contract_no LIKE 'IT-P-C%'
                """);
        // 계약·지급단계마다 월 판정 6건. 1,000건 중 1건꼴로 최신 판정이 주의다.
        jdbc.update("""
                INSERT INTO fgc.cap_check(contract_id, payment_stage, cap_rule_set_id, check_kind, as_of_date,
                    base_premium_amount, refund_12m_amount, compliance_deduction_amount, limit_amount, included_amount,
                    remaining_amount, usage_pct, result_status, checked_at)
                SELECT c.contract_id, r.payment_stage, r.cap_rule_set_id, 'REALTIME', (DATE '1997-01-31' + m * 30),
                       100000, 0, 0, 1200000, 0, 1200000, 0,
                       CASE WHEN c.contract_id % 1000 = 0 AND m = 5 THEN 'WARNING' ELSE 'NORMAL' END,
                       TIMESTAMPTZ '1997-01-31 09:00+09' + m * INTERVAL '30 days'
                  FROM fgc.insurance_contract c
                 CROSS JOIN generate_series(0, 5) m
                 CROSS JOIN LATERAL (SELECT DISTINCT ON (payment_stage) payment_stage, cap_rule_set_id
                                       FROM fgc.cap_rule_set ORDER BY payment_stage, cap_rule_set_id) r
                 WHERE c.contract_no LIKE 'IT-P-C%'
                """);
        // 계약 20건 중 1건에 미처리 예외
        jdbc.update("""
                INSERT INTO fgc.exception_case(exception_key, exception_type, severity, status, source_entity_type,
                    source_entity_id, title, contract_id, created_at)
                SELECT 'IT-P:' || c.contract_id, 'DATA_QUALITY', 'WARNING', 'NEW', 'IT', c.contract_id::text, '성능 시험', c.contract_id,
                       TIMESTAMPTZ '1997-03-01 09:00+09'
                  FROM fgc.insurance_contract c
                 WHERE c.contract_no LIKE 'IT-P-C%' AND c.contract_id % 20 = 0
                """);
        jdbc.execute("ANALYZE fgc.agent");
        jdbc.execute("ANALYZE fgc.insurance_contract");
        jdbc.execute("ANALYZE fgc.contract_status_event");
        jdbc.execute("ANALYZE fgc.cap_check");
        jdbc.execute("ANALYZE fgc.exception_case");
    }
}
