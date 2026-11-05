package com.susukkang.fgc.contract.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.auth.service.FgcUserDetailsService;
import com.susukkang.fgc.contract.repository.ContractEventJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FUN-027 / CT-C02 조회 단계: 실제 worker와 FUN-028 처리기가 실제 PostgreSQL에 남긴 결과를 IF-API-16·89 HTTP 응답으로 확인한다.
 * worker가 작업마다 커밋하므로 전용 DB를 쓴다. 대기·실패·소진 구분은 ContractStatusEventIntegrationTest가 확인한다.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:tc:postgresql:17:///fgc_status_history?currentSchema=fgc")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@Timeout(60)
class ContractStatusHistoryIntegrationTest {

    private static final String JOB = PaymentHoldFollowUpProcessor.JOB;

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ContractStatusFollowUpService followUps;
    @Autowired private ContractEventJobRepository jobs;
    @Autowired private FgcUserDetailsService users;

    private FgcUserDetails settle;

    @BeforeEach
    void setUp() {
        settle = (FgcUserDetails) users.loadUserByUsername("settle01");
    }

    @Test
    @DisplayName("CT-C02 미확정만 제어한 결과가 이력 요약과 영향 조회에 같게 보이고 지급 보류와 회차 중단이 구분된다")
    void historyAndEffectsShowWhatTheWorkerDid() throws Exception {
        long contract = contract();
        statusEvent(contract, 1, "ACTIVE", "2026-03-01T00:00:00+09:00");
        long lapse = statusEvent(contract, 2, "LAPSED", "2026-09-01T00:00:00+09:00");
        long header = header(contract);
        long confirmedLine = line(header, 1, "2026-09-10", "CONFIRMED");
        long plannedLine = line(header, 2, "2026-09-25", "PLANNED");
        long draftPayment = payment(contract, "2026-09-20", false);
        long confirmedPayment = payment(contract, "2026-09-21", true);

        jobs.insertPending(lapse, JOB);
        statusEvents(contract)
                .andExpect(jsonPath("$.data[1].followUp.status").value("PENDING"))
                .andExpect(jsonPath("$.data[1].processings").isEmpty());
        // 처리 전에는 영향이 없다. 화면은 이 0건을 대기 상태로 읽는다.
        effects(contract, lapse, "").andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));

        followUps.runDueJobs();

        statusEvents(contract)
                .andExpect(jsonPath("$.data[1].followUp.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[1].processings[0].job").value(JOB))
                .andExpect(jsonPath("$.data[1].processings[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[1].processings[0].summary.heldPayments").value(1))
                .andExpect(jsonPath("$.data[1].processings[0].summary.resumedPayments").value(0))
                .andExpect(jsonPath("$.data[1].processings[0].summary.changedScheduleLines").value(1))
                .andExpect(jsonPath("$.data[1].processings[0].summary.excludedCount").value(2));

        // 정렬은 대상 유형, ID 순이다. 지급 보류(HELD)와 회차 중단(CANCELLED), 제외(EXCLUDED)가 각각 다른 값으로 온다.
        effects(contract, lapse, "?page=1&size=20")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(4))
                .andExpect(jsonPath("$.data.content[0].entityType").value("PAYMENT"))
                .andExpect(jsonPath("$.data.content[0].entityId").value(draftPayment))
                .andExpect(jsonPath("$.data.content[0].previousState").value("DRAFT"))
                .andExpect(jsonPath("$.data.content[0].nextState").value("HELD"))
                .andExpect(jsonPath("$.data.content[0].amount").value(5000.00))
                .andExpect(jsonPath("$.data.content[0].policyVersionId").isNumber())
                .andExpect(jsonPath("$.data.content[0].reasonCode").value("STATUS_INACTIVE"))
                .andExpect(jsonPath("$.data.content[1].entityId").value(confirmedPayment))
                .andExpect(jsonPath("$.data.content[1].previousState").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.content[1].nextState").value("EXCLUDED"))
                .andExpect(jsonPath("$.data.content[1].reasonCode").value("ALREADY_CONFIRMED"))
                .andExpect(jsonPath("$.data.content[2].entityType").value("SCHEDULE_LINE"))
                .andExpect(jsonPath("$.data.content[2].entityId").value(confirmedLine))
                .andExpect(jsonPath("$.data.content[2].nextState").value("EXCLUDED"))
                .andExpect(jsonPath("$.data.content[3].entityId").value(plannedLine))
                .andExpect(jsonPath("$.data.content[3].previousState").value("PLANNED"))
                .andExpect(jsonPath("$.data.content[3].nextState").value("CANCELLED"));
        effects(contract, lapse, "?page=2&size=3")
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.content.length()").value(1));

        // 뒤 사건을 처리해도 앞 사건의 영향 근거는 그대로다(append-only).
        JsonNode before = data(effects(contract, lapse, ""));
        long terminated = statusEvent(contract, 3, "TERMINATED", "2026-10-01T00:00:00+09:00");
        jobs.insertPending(terminated, JOB);
        followUps.runDueJobs();
        assertThat(data(effects(contract, lapse, ""))).isEqualTo(before);
    }

    @Test
    @DisplayName("처리는 끝났지만 바뀐 대상이 없는 사건은 요약 0건과 영향 0건이고, 금액은 null과 실제 0을 구분한다")
    void successWithoutTargetsAndNullAmountStayDistinct() throws Exception {
        long contract = contract();
        statusEvent(contract, 1, "ACTIVE", "2026-03-01T00:00:00+09:00");
        long lapse = statusEvent(contract, 2, "LAPSED", "2026-09-01T00:00:00+09:00");
        jobs.insertPending(lapse, JOB);

        followUps.runDueJobs();

        statusEvents(contract)
                .andExpect(jsonPath("$.data[1].followUp.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[1].processings[0].summary.heldPayments").value(0))
                .andExpect(jsonPath("$.data[1].processings[0].summary.resumedPayments").value(0))
                .andExpect(jsonPath("$.data[1].processings[0].summary.changedScheduleLines").value(0))
                .andExpect(jsonPath("$.data[1].processings[0].summary.excludedCount").value(0));
        effects(contract, lapse, "").andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));

        jdbc.update("""
                INSERT INTO fgc.contract_status_event_effect(contract_status_event_id, processing_job, entity_type, entity_id,
                    next_state, amount, reason_code)
                VALUES (?, 'AmountCheck', 'PAYMENT', 1, 'EXCLUDED', NULL, 'ALREADY_CONFIRMED'),
                       (?, 'AmountCheck', 'PAYMENT', 2, 'EXCLUDED', 0, 'ALREADY_CONFIRMED')
                """, lapse, lapse);
        JsonNode rows = data(effects(contract, lapse, "")).path("content");
        assertThat(rows.get(0).path("amount").isNull()).isTrue();
        assertThat(rows.get(1).path("amount").isNull()).isFalse();
        assertThat(rows.get(1).path("amount").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("CT-C02 실제 실효·부활 worker 결과를 스케줄 상세의 원천 사건과 대조한다")
    void scheduleSourcesFollowRealHoldAndResumeJobs() throws Exception {
        long contract = contract();
        statusEvent(contract, 1, "ACTIVE", "2026-03-01T00:00:00+09:00");
        long header = header(contract);
        long confirmed = line(header, 1, "2026-08-10", "CONFIRMED");
        long lapsePeriod = line(header, 2, "2026-09-25", "PLANNED");
        long future = line(header, 3, "2026-10-25", "PLANNED");
        long lapse = statusEvent(contract, 2, "LAPSED", "2026-09-01T00:00:00+09:00");
        jobs.insertPending(lapse, PaymentHoldFollowUpProcessor.JOB);
        followUps.runDueJobs();

        assertScheduleSource(header, confirmed, "CONFIRMED", null);
        assertScheduleSource(header, lapsePeriod, "CANCELLED", lapse);
        assertScheduleSource(header, future, "CANCELLED", lapse);

        long revival = statusEvent(contract, 3, "REVIVED", "2026-10-15T00:00:00+09:00");
        jobs.insertPending(revival, PaymentResumeFollowUpProcessor.JOB);
        followUps.runDueJobs();

        assertScheduleSource(header, confirmed, "CONFIRMED", null);
        // NO_RETROACTIVE is an exclusion, not the cause of the existing cancellation.
        assertScheduleSource(header, lapsePeriod, "CANCELLED", lapse);
        assertScheduleSource(header, future, "PLANNED", revival);
        effects(contract, revival, "").andExpect(status().isOk());
    }

    private void assertScheduleSource(long header, long lineId, String state, Long source) throws Exception {
        JsonNode lines = data(mvc.perform(get("/api/v1/schedules/{id}", header).with(user(settle)))
                .andExpect(status().isOk())).path("lines");
        JsonNode line = java.util.stream.StreamSupport.stream(lines.spliterator(), false)
                .filter(row -> row.path("scheduleLineId").asLong() == lineId).findFirst().orElseThrow();
        assertThat(line.path("lineStatus").asText()).isEqualTo(state);
        if (source == null) assertThat(line.path("sourceStatusEventId").isNull()).isTrue();
        else assertThat(line.path("sourceStatusEventId").asLong()).isEqualTo(source);
    }

    @Test
    @DisplayName("FUN-029 처리기가 남기는 값도 요약에 들어간다: 보류 해제, 회차 복원과 재중단, 소급 제외")
    void summaryCountsTheRevivalJobValues() throws Exception {
        long contract = contract();
        statusEvent(contract, 1, "ACTIVE", "2026-03-01T00:00:00+09:00");
        long revival = statusEvent(contract, 2, "REVIVED", "2026-10-15T00:00:00+09:00");
        String job = "PaymentResumeJob";
        jdbc.update("""
                INSERT INTO fgc.contract_event_job(contract_status_event_id, processing_job, state, attempt_count)
                VALUES (?, ?, 'SUCCEEDED', 1)
                """, revival, job);
        jdbc.update("""
                INSERT INTO fgc.contract_status_event_processing(contract_status_event_id, processing_job, processing_status)
                VALUES (?, ?, 'SUCCEEDED')
                """, revival, job);
        for (Object[] effect : new Object[][]{
                {"PAYMENT", 1L, "DRAFT", "RELEASED", "STATUS_ACTIVE"},
                {"PAYMENT", 2L, "DRAFT", "EXCLUDED", "NO_RETROACTIVE"},
                {"SCHEDULE_LINE", 3L, "CANCELLED", "PLANNED", "STATUS_ACTIVE"},
                {"SCHEDULE_LINE", 4L, "PLANNED", "CANCELLED", "STATUS_INACTIVE"},
                {"SCHEDULE_LINE", 5L, "CANCELLED", "EXCLUDED", "NO_RETROACTIVE"}}) {
            jdbc.update("""
                    INSERT INTO fgc.contract_status_event_effect(contract_status_event_id, processing_job, entity_type,
                        entity_id, previous_state, next_state, reason_code)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, revival, job, effect[0], effect[1], effect[2], effect[3], effect[4]);
        }

        statusEvents(contract)
                .andExpect(jsonPath("$.data[1].followUp.jobs[0].job").value(job))
                .andExpect(jsonPath("$.data[1].processings[0].summary.heldPayments").value(0))
                .andExpect(jsonPath("$.data[1].processings[0].summary.resumedPayments").value(1))
                .andExpect(jsonPath("$.data[1].processings[0].summary.changedScheduleLines").value(2))
                .andExpect(jsonPath("$.data[1].processings[0].summary.excludedCount").value(2));
    }

    @Test
    @DisplayName("다른 계약의 사건 ID로는 영향을 읽을 수 없고 잘못된 페이지 값은 거절한다")
    void otherContractsEventAndBadPagingAreRejected() throws Exception {
        long contract = contract();
        long other = contract();
        long event = statusEvent(contract, 1, "ACTIVE", "2026-03-01T00:00:00+09:00");

        effects(other, event, "")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-004"));
        effects(contract, event, "?page=0")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"));
        effects(contract, event, "?size=101")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"));
    }

    private ResultActions statusEvents(long contract) throws Exception {
        return mvc.perform(get("/api/v1/contracts/{id}/status-events", contract).with(user(settle))).andExpect(status().isOk());
    }

    private ResultActions effects(long contract, long event, String query) throws Exception {
        return mvc.perform(get("/api/v1/contracts/" + contract + "/status-events/" + event + "/effects" + query).with(user(settle)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    private long contract() {
        return jdbc.queryForObject("""
                INSERT INTO fgc.insurance_contract(insurer_id, product_offering_id, contract_no, contract_date, agent_id,
                    organization_id, premium_per_cycle_amount, first_premium_amount, monthly_equivalent_first_premium,
                    payment_term_months, current_status, data_origin)
                SELECT insurer_id, product_offering_id, ?, DATE '2026-03-01', agent_id, organization_id, 100000, 100000, 100000,
                       120, 'ACTIVE', 'MANUAL'
                  FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1
                RETURNING contract_id
                """, Long.class, "H719-" + UUID.randomUUID());
    }

    private long statusEvent(long contractId, int eventSeq, String status, String effectiveAt) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.contract_status_event(contract_id, event_seq, new_status, effective_at, received_at,
                    source_system, source_event_key, data_origin)
                VALUES (?, ?, ?, ?::timestamptz, ?::timestamptz, 'TEST-719', ?, 'MANUAL')
                RETURNING contract_status_event_id
                """, Long.class, contractId, eventSeq, status, effectiveAt, effectiveAt, UUID.randomUUID().toString());
    }

    private long header(long contractId) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.schedule_header(contract_id, payment_stage, policy_version_id, schedule_version_no,
                    schedule_purpose, schedule_regime)
                VALUES (?, 'GA_TO_FC', (SELECT min(policy_version_id) FROM fgc.policy_version), 1, 'OPERATIONAL', 'CURRENT')
                RETURNING schedule_header_id
                """, Long.class, contractId);
    }

    private long line(long headerId, int installment, String dueDate, String status) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.schedule_line(schedule_header_id, line_no, installment_no, contract_month_no, due_date,
                    commission_item_id, basis_code, basis_amount, calculation_type, fixed_amount, expected_amount, line_status)
                VALUES (?, ?, ?, ?, ?::date, (SELECT min(commission_item_id) FROM fgc.commission_item),
                        'TEST', 1000, 'FIXED', 1000, 1000, ?)
                RETURNING schedule_line_id
                """, Long.class, headerId, installment, installment, installment, dueDate, status);
    }

    private long payment(long contractId, String attributionDate, boolean confirmed) {
        Long id = jdbc.queryForObject("""
                INSERT INTO fgc.commission_transaction(payment_stage, source_type, source_business_key, commission_item_id,
                    settlement_month, amount, cashflow_type)
                VALUES ('INSURER_TO_GA', 'INSURER_STATEMENT', ?, (SELECT min(commission_item_id) FROM fgc.commission_item),
                        date_trunc('month', ?::date)::date, 5000, 'PAYMENT')
                RETURNING commission_transaction_id
                """, Long.class, "H719-" + UUID.randomUUID(), attributionDate);
        jdbc.update("""
                INSERT INTO fgc.transaction_attribution(commission_transaction_id, contract_id, attribution_date,
                    attribution_month, attributed_amount, inclusion_status_snapshot, attribution_method)
                VALUES (?, ?, ?::date, date_trunc('month', ?::date)::date, 5000, 'INCLUDED', 'DIRECT')
                """, id, contractId, attributionDate, attributionDate);
        if (confirmed) {
            jdbc.update("UPDATE fgc.commission_transaction SET status = 'CONFIRMED' WHERE commission_transaction_id = ?", id);
        }
        return id;
    }
}
