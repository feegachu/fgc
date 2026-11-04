package com.susukkang.fgc.dashboard;

import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.auth.service.AuthTokenService;
import com.susukkang.fgc.auth.service.FgcUserDetailsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IF-API-68 관리자 홈 GA·설계사 현황(FUN-080). HTTP 바인딩부터 PostgreSQL 집계까지 mock 없이 지난다.
 *
 * <p>과거 기준일(1999년)은 시드 설계사(가장 이른 위촉일 2019년)가 들어오지 않아 전체 건수를 그대로 기대값으로 적을 수 있다.
 */
@SpringBootTest(properties = "fgc.batch.daily-changed-contract.enabled=false")
@AutoConfigureMockMvc
@Transactional
class HomeSummaryApiIntegrationTest {

    private static final String AS_OF = "1999-06-30";
    private static final String SUMMARY = "/api/v1/home/summary";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuthTokenService tokens;
    @Autowired private FgcUserDetailsService users;

    private long branchA;
    private long agent1;

    @BeforeEach
    void fixture() {
        long division = org("IT-H-D", "시험본부", "DIVISION", null, "1999-01-01", null);
        branchA = org("IT-H-B-A", "시험A지점", "BRANCH", division, "1999-01-01", null);
        long teamA = org("IT-H-B-A-T", "시험A1팀", "TEAM", branchA, "1999-01-01", null);
        long branchB = org("IT-H-B-B", "시험B지점", "BRANCH", division, "1999-01-01", null);
        org("IT-H-B-C", "폐쇄지점", "BRANCH", division, "1999-01-01", "1999-01-31");
        org("IT-H-B-D", "개설전지점", "BRANCH", division, "1999-07-01", null);
        long closedWithContract = org("IT-H-B-E", "시험E지점", "BRANCH", division, "1999-01-01", "1999-01-31");

        agent1 = agent("IT-H-A1", "시험활동", teamA, "1999-01-01", null, "ACTIVE", true);
        long agent2 = agent("IT-H-A2", "시험비활동", branchA, "1999-01-01", null, "INACTIVE", true);
        long agent3 = agent("IT-H-A3", "시험해촉", teamA, "1999-01-01", "1999-06-29", "TERMINATED", true);
        long agent4 = agent("IT-H-A4", "시험당일해촉", branchA, "1999-01-01", AS_OF, "TERMINATED", true);
        agent("IT-H-A5", "시험위촉전", teamA, "1999-07-01", null, "ACTIVE", true);
        long agent6 = agent("IT-H-A6", "시험본부장", division, "1999-01-01", null, "ACTIVE", true);
        long agent7 = agent("IT-H-A7", "시험사용중지", teamA, "1999-01-01", null, "ACTIVE", false);
        long agent8 = agent("IT-H-A8", "시험당일위촉", branchB, AS_OF, null, "ACTIVE", true);

        long c1 = contract("IT-H-C1", agent1, teamA, "1999-02-01", "ACTIVE");
        long c2 = contract("IT-H-C2", agent1, teamA, "1999-02-01", "TERMINATED");
        event(c2, 1, "ACTIVE", "1999-02-01 09:00:00+09");
        event(c2, 2, "TERMINATED", "1999-07-01 00:00:00+09");
        long c3 = contract("IT-H-C3", agent1, teamA, "1999-02-01", "ACTIVE");
        event(c3, 1, "UNPAID", "1999-06-30 23:59:59+09");
        event(c3, 2, "ACTIVE", "1999-07-01 00:00:00+09");
        long c4 = contract("IT-H-C4", agent2, branchA, "1999-02-01", "LAPSED");
        event(c4, 1, "LAPSED", "1999-05-30 23:59:59+09");
        contract("IT-H-C5", agent2, branchA, "1999-07-01", "ACTIVE");
        long c6 = contract("IT-H-C6", agent3, teamA, "1999-02-01", "REVIVED");
        event(c6, 1, "REVIVED", "1999-05-31 00:00:00+09");
        long c7 = contract("IT-H-C7", agent6, division, "1999-02-01", "ACTIVE");
        event(c7, 1, "ACTIVE", "1999-06-15 09:00:00+09");
        contract("IT-H-C8", agent8, branchB, AS_OF, "MATURED");
        contract("IT-H-C9", agent8, branchB, AS_OF, "ACTIVE");
        long c10 = contract("IT-H-C10", agent4, branchA, "1999-02-01", "ACTIVE");
        contract("IT-H-C11", agent8, closedWithContract, "1999-02-01", "ACTIVE");
        long c12 = contract("IT-H-C12", agent1, teamA, "1999-02-01", "TERMINATED");
        // 기준일 전 사건이 없으면 기준일 뒤 첫 사건의 이전 상태가 그날 상태다.
        jdbc.update("""
                INSERT INTO fgc.contract_status_event(contract_id, event_seq, previous_status, new_status, effective_at,
                    received_at, reason_code, source_system, source_event_key)
                VALUES (?, 1, 'ACTIVE', 'TERMINATED', TIMESTAMPTZ '1999-08-01 09:00:00+09', TIMESTAMPTZ '1999-08-01 09:00:00+09',
                    'TEST', 'IT-HOME', ?)
                """, c12, "IT-HOME:" + c12 + ":1");

        exception("X1", "NEW", c1, null, "1999-03-01 09:00:00+09");
        exception("X2", "IN_REVIEW", null, agent1, "1999-03-01 09:00:00+09");
        exception("X3", "RESOLVED", c1, null, "1999-03-01 09:00:00+09");
        exception("X4", "NEW", c4, null, "1999-07-01 00:00:00+09");
        exception("X5", "NEW", c6, null, "1999-06-30 23:59:59+09");
        exception("X6", "NEW", null, null, "1999-03-01 09:00:00+09");
        exception("X7", "NEW", null, agent7, "1999-03-01 09:00:00+09");

        // 같은 계약의 두 지급단계가 모두 주의여도 계약 1건이다.
        cap(c1, "GA_TO_FC", AS_OF, "WARNING", "1999-06-30 09:00:00+09", "{}");
        cap(c1, "INSURER_TO_GA", AS_OF, "WARNING", "1999-06-30 09:00:00+09", "{}");
        // 최신 판정이 정상이면 예전 주의는 세지 않는다.
        cap(c2, "GA_TO_FC", "1999-05-31", "WARNING", "1999-05-31 09:00:00+09", "{}");
        cap(c2, "GA_TO_FC", AS_OF, "NORMAL", "1999-06-30 09:00:00+09", "{}");
        // 기준일 뒤 판정, 삭제 후보 판정, 위반 판정은 한도 근접이 아니다.
        cap(c3, "GA_TO_FC", "1999-07-01", "WARNING", "1999-07-01 09:00:00+09", "{}");
        cap(c12, "GA_TO_FC", AS_OF, "WARNING", "1999-06-30 09:00:00+09", "{\"deletedCandidateTransactionId\": 1}");
        cap(c6, "GA_TO_FC", AS_OF, "VIOLATION", "1999-06-30 09:00:00+09", "{}");
        cap(c10, "GA_TO_FC", AS_OF, "WARNING", "1999-06-30 09:00:00+09", "{}");
    }

    @Test
    void 과거_기준일의_건수와_합계를_돌려준다() throws Exception {
        summary(AS_OF)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.asOf").value(AS_OF))
                // 등록: A1, A2, A4(해촉일 당일), A6, A7(사용중지), A8(위촉일 당일). A5는 위촉 전이다.
                .andExpect(jsonPath("$.data.agents.registered").value(6))
                // 활동: 상태가 활동이고 기준정보가 사용 중인 A1, A6, A8
                .andExpect(jsonPath("$.data.agents.active").value(3))
                // 해촉: 기준일 전에 위촉이 끝난 A3
                .andExpect(jsonPath("$.data.agents.terminated").value(1))
                .andExpect(jsonPath("$.data.branches[*].name").value(contains("시험A지점", "시험B지점", "시험E지점", "시험본부")))
                // 팀 소속은 지점으로 올려 세고, 해촉자의 계약도 지점의 유효 계약에 남는다.
                .andExpect(jsonPath("$.data.branches[0].organizationId").value(branchA))
                .andExpect(jsonPath("$.data.branches[0].agentCount").value(4))
                .andExpect(jsonPath("$.data.branches[0].activeContractCount").value(6))
                .andExpect(jsonPath("$.data.branches[0].attentionCount").value(4))
                .andExpect(jsonPath("$.data.branches[1].agentCount").value(1))
                .andExpect(jsonPath("$.data.branches[1].activeContractCount").value(1))
                .andExpect(jsonPath("$.data.branches[1].attentionCount").value(0))
                // 폐쇄된 지점도 유효 계약이 남아 있으면 합계가 맞도록 보인다.
                .andExpect(jsonPath("$.data.branches[2].agentCount").value(0))
                .andExpect(jsonPath("$.data.branches[2].activeContractCount").value(1))
                // 지점이 없는 본부 소속은 본부 행으로 남겨 소계 합이 등록 수와 같다.
                .andExpect(jsonPath("$.data.branches[3].agentCount").value(1))
                .andExpect(jsonPath("$.data.branches[3].activeContractCount").value(1));
        // 같은 기준일의 IF-API-07 건수와 맞는다. 기본은 등록, 해촉 포함은 등록 + 해촉이다.
        mvc.perform(agents()).andExpect(jsonPath("$.data.totalElements").value(6));
        mvc.perform(agents().param("includeTerminated", "true")).andExpect(jsonPath("$.data.totalElements").value(7));
    }

    @Test
    void 확인할_설계사는_사유별로_세고_계약별_판정을_금액으로_합치지_않는다() throws Exception {
        summary(AS_OF)
                .andExpect(jsonPath("$.data.attentionTotal").value(4))
                .andExpect(jsonPath("$.data.attentionAgents[*].code").value(contains("IT-H-A1", "IT-H-A3", "IT-H-A7", "IT-H-A4")))
                .andExpect(jsonPath("$.data.attentionAgents[0].agentId").value(agent1))
                .andExpect(jsonPath("$.data.attentionAgents[0].name").value("시험활동"))
                .andExpect(jsonPath("$.data.attentionAgents[0].branch").value("시험A지점"))
                .andExpect(jsonPath("$.data.attentionAgents[0].statusLabel").value("활동"))
                .andExpect(jsonPath("$.data.attentionAgents[0].activeYn").value(true))
                .andExpect(jsonPath("$.data.attentionAgents[0].contractCount").value(4))
                .andExpect(jsonPath("$.data.attentionAgents[0].reasons[*].label")
                        .value(contains("미처리 예외", "한도 근접", "최근 상태 변화")))
                .andExpect(jsonPath("$.data.attentionAgents[0].reasons[*].code")
                        .value(contains("OPEN_EXCEPTION", "NEAR_LIMIT", "RECENT_STATUS_CHANGE")))
                .andExpect(jsonPath("$.data.attentionAgents[0].reasons[*].count").value(contains(2, 1, 1)))
                // 해촉자의 미처리 건도 사라지지 않게 보이되 해촉으로 구분한다.
                .andExpect(jsonPath("$.data.attentionAgents[1].statusLabel").value("해촉"))
                .andExpect(jsonPath("$.data.attentionAgents[1].reasons[*].code")
                        .value(contains("OPEN_EXCEPTION", "RECENT_STATUS_CHANGE")))
                .andExpect(jsonPath("$.data.attentionAgents[1].reasons[*].count").value(contains(1, 1)))
                // 상태는 활동이어도 기준정보 사용 중지는 활동 수에서 빠지므로 목록에서도 구분할 수 있어야 한다.
                .andExpect(jsonPath("$.data.attentionAgents[2].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.attentionAgents[2].activeYn").value(false))
                .andExpect(jsonPath("$.data.attentionAgents[3].reasons[*].code").value(contains("NEAR_LIMIT")))
                .andExpect(jsonPath("$.data.attentionAgents[3].reasons[*].count").value(contains(1)))
                .andExpect(jsonPath("$.data.basis").value(containsString("최근 30일")));
    }

    @Test
    void 오늘_이후_기준일은_현재_계약_상태로_세고_생략하면_오늘이다() throws Exception {
        String today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        summary(null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.asOf").value(today))
                // C2, C12는 해지됐고 C4는 실효다. C1, C3, C5, C6, C10이 남는다.
                .andExpect(jsonPath("$.data.branches[?(@.name == '시험A지점')].activeContractCount").value(contains(5)))
                // A1, A2, A5, A7. 해촉일이 지난 A3, A4는 빠진다.
                .andExpect(jsonPath("$.data.branches[?(@.name == '시험A지점')].agentCount").value(contains(4)));
    }

    @Test
    void 상위_10명은_사유_건수와_코드로_정렬하고_전체_인원은_따로_준다() throws Exception {
        String asOf = "1998-06-30";
        long branch = org("IT-H-B-T", "시험정렬지점", "BRANCH", null, "1998-01-01", null);
        // 코드를 거꾸로 넣어 입력 순서가 결과에 새지 않음을 본다.
        List<Long> agents = new ArrayList<>();
        for (int n = 12; n >= 1; n--) {
            agents.add(agent("IT-H-T%02d".formatted(n), "정렬" + n, branch, "1998-01-01", null, "ACTIVE", true));
        }
        for (int i = 0; i < agents.size(); i++) {
            exception("T" + i, "NEW", null, agents.get(i), "1998-03-01 09:00:00+09");
        }
        // 첫 행 agents[0]은 T12다. 건수가 많으면 코드보다 앞선다.
        exception("T-EXTRA", "NEW", null, agents.get(0), "1998-03-01 09:00:00+09");

        summary(asOf)
                .andExpect(jsonPath("$.data.attentionTotal").value(12))
                .andExpect(jsonPath("$.data.branches[0].attentionCount").value(12))
                .andExpect(jsonPath("$.data.attentionAgents[*].code").value(contains(
                        "IT-H-T12", "IT-H-T01", "IT-H-T02", "IT-H-T03", "IT-H-T04",
                        "IT-H-T05", "IT-H-T06", "IT-H-T07", "IT-H-T08", "IT-H-T09")));
    }

    @Test
    void 지점으로_찾으면_아래_팀_소속까지_나오고_해촉자는_요청할_때만_나온다() throws Exception {
        MockHttpServletRequestBuilder search = agents().param("branchId", String.valueOf(branchA));
        mvc.perform(search)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].agentCode")
                        .value(contains("IT-H-A1", "IT-H-A2", "IT-H-A4", "IT-H-A7")));
        mvc.perform(agents().param("branchId", String.valueOf(branchA)).param("includeTerminated", "true"))
                .andExpect(jsonPath("$.data.totalElements").value(5))
                .andExpect(jsonPath("$.data.content[*].agentCode")
                        .value(contains("IT-H-A1", "IT-H-A2", "IT-H-A3", "IT-H-A4", "IT-H-A7")));
        mvc.perform(agents().param("branchId", String.valueOf(branchA)).param("keyword", "시험해촉")
                        .param("includeTerminated", "true"))
                .andExpect(jsonPath("$.data.content[*].agentCode").value(contains("IT-H-A3")))
                .andExpect(jsonPath("$.data.content[0].agentStatusLabel").value("해촉"));
        mvc.perform(agents().param("branchId", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("branchId"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "GA_ADMIN", "SETTLEMENT", "COMPLIANCE"})
    void 내부_역할은_현황을_본다(String role) throws Exception {
        mvc.perform(get(SUMMARY).param("asOf", AS_OF).with(user(principal(role))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agents.registered").value(6));
    }

    @Test
    void 설계사_역할과_미인증_요청은_막는다() throws Exception {
        mvc.perform(get(SUMMARY).with(user(principal("AGENT"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FGC-AUTH-003"));
        mvc.perform(get(SUMMARY)).andExpect(status().isUnauthorized());
    }

    /** user() 는 토큰 검증을 건너뛴다. 실제 로그인 토큰으로 URL 규칙(FUN-085)과 @PreAuthorize 를 함께 지난다. */
    @Test
    void 실제_로그인_토큰으로도_설계사는_막고_내부_역할은_연다() throws Exception {
        String agent = tokens.login((FgcUserDetails) users.loadUserByUsername("agent01")).accessToken();
        String settlement = tokens.login((FgcUserDetails) users.loadUserByUsername("settle01")).accessToken();
        for (String url : List.of(SUMMARY, "/api/v1/exceptions/counts")) {
            mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + agent))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("FGC-AUTH-003"));
            mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + settlement))
                    .andExpect(status().isOk());
        }
    }

    /** 기준일의 끝과 최근 30일의 시작을 계산해 DB 에 넘기므로 달력 끝 값에서도 오류가 나지 않아야 한다. */
    @ParameterizedTest
    @ValueSource(strings = {"0001-01-01", "9999-12-31"})
    void 달력_끝의_기준일도_조회한다(String asOf) throws Exception {
        summary(asOf).andExpect(status().isOk()).andExpect(jsonPath("$.data.asOf").value(asOf));
    }

    @Test
    void 기준일_형식이_틀리면_입력_오류다() throws Exception {
        mvc.perform(get(SUMMARY).param("asOf", "1999-13-01").with(user(principal("SETTLEMENT"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"))
                .andExpect(jsonPath("$.error.field").value("asOf"));
    }

    private ResultActions summary(String asOf) throws Exception {
        MockHttpServletRequestBuilder request = get(SUMMARY).with(user(principal("SETTLEMENT")));
        return mvc.perform(asOf == null ? request : request.param("asOf", asOf));
    }

    private MockHttpServletRequestBuilder agents() {
        return get("/api/v1/base/agents").param("asOf", AS_OF).with(user(principal("SETTLEMENT")));
    }

    private long org(String code, String name, String type, Long parent, String from, String to) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.organization(organization_code, organization_name, organization_type, parent_id, effective_from, effective_to)
                VALUES (?, ?, ?, ?, CAST(? AS date), CAST(? AS date))
                RETURNING organization_id
                """, Long.class, code, name, type, parent, from, to);
    }

    private long agent(String code, String name, long org, String appointed, String terminated, String status, boolean active) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.agent(organization_id, agent_code, agent_name, rank_code, appointment_date, termination_date, agent_status, active_yn)
                VALUES (?, ?, ?, 'FC', CAST(? AS date), CAST(? AS date), ?, ?)
                RETURNING agent_id
                """, Long.class, org, code, name, appointed, terminated, status, active);
    }

    private long contract(String no, long agent, long org, String date, String status) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.insurance_contract (
                    insurer_id, product_offering_id, contract_no, contract_date, agent_id, organization_id,
                    premium_per_cycle_amount, first_premium_amount, monthly_equivalent_first_premium,
                    premium_conversion_rule_code, payment_cycle_code, payment_term_months, current_status, data_origin)
                SELECT insurer_id, product_offering_id, ?, CAST(? AS date), ?, ?,
                       premium_per_cycle_amount, first_premium_amount, monthly_equivalent_first_premium,
                       premium_conversion_rule_code, payment_cycle_code, payment_term_months, ?, 'MANUAL'
                  FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1
                RETURNING contract_id
                """, Long.class, no, date, agent, org, status);
    }

    private void event(long contract, int seq, String status, String effectiveAt) {
        jdbc.update("""
                INSERT INTO fgc.contract_status_event(contract_id, event_seq, new_status, effective_at, received_at,
                    reason_code, source_system, source_event_key)
                VALUES (?, ?, ?, CAST(? AS timestamptz), CAST(? AS timestamptz), 'TEST', 'IT-HOME', ?)
                """, contract, seq, status, effectiveAt, effectiveAt, "IT-HOME:" + contract + ":" + seq);
    }

    private void exception(String key, String status, Long contract, Long agent, String createdAt) {
        jdbc.update("""
                INSERT INTO fgc.exception_case(exception_key, exception_type, severity, status, source_entity_type,
                    source_entity_id, title, contract_id, agent_id, created_at)
                VALUES (?, 'DATA_QUALITY', 'WARNING', ?, 'IT', ?, '시험 예외', ?, ?, CAST(? AS timestamptz))
                """, "IT-HOME:" + key, status, key, contract, agent, createdAt);
    }

    private void cap(long contract, String stage, String asOf, String status, String checkedAt, String snapshot) {
        jdbc.update("""
                INSERT INTO fgc.cap_check(contract_id, payment_stage, cap_rule_set_id, check_kind, as_of_date,
                    base_premium_amount, refund_12m_amount, compliance_deduction_amount, limit_amount, included_amount,
                    remaining_amount, usage_pct, result_status, calculation_snapshot, checked_at)
                SELECT ?, ?, cap_rule_set_id, 'REALTIME', CAST(? AS date), 100000, 0, 0, 1200000, 0, 1200000, 0, ?,
                       CAST(? AS jsonb), CAST(? AS timestamptz)
                  FROM fgc.cap_rule_set WHERE payment_stage = ? ORDER BY cap_rule_set_id LIMIT 1
                """, contract, stage, asOf, status, snapshot, checkedAt, stage);
    }

    private FgcUserDetails principal(String role) {
        AppUserView view = new AppUserView();
        view.setUserId(jdbc.queryForObject("SELECT user_id FROM fgc.app_user ORDER BY user_id LIMIT 1", Long.class));
        view.setLoginId("it-home");
        view.setPasswordHash("{bcrypt}dummy");
        view.setUserName("it-home");
        view.setRoleCode(role);
        return new FgcUserDetails(view, true, true);
    }
}
