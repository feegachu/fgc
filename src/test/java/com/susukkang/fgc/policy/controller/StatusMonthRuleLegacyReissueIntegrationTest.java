package com.susukkang.fgc.policy.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.policy.service.ScheduleEligibilityPolicyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * #648(PR #806 리뷰): db/migration만 적용한 운영 DB에서 V77 뒤 복구 절차(POL-W01 새 버전 작성)를 실제 정책 API로 끝까지 확인한다.
 * 이 DB에는 v1을 승인한 사용자가 없어 v1의 코드 없는 문서 경로가 승인 근거가 아니다. 데모 DB에서는 데모 v2가 같은 경로를
 * 승인본으로 갖고 있어 이 거절이 드러나지 않으므로 전용 DB를 쓴다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17:///fgc_status_month_rule_reissue?currentSchema=fgc",
        "spring.flyway.locations=classpath:db/migration"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@Transactional
class StatusMonthRuleLegacyReissueIntegrationTest {
    private static final LocalDate EXISTING_CONTRACT = LocalDate.of(2026, 3, 1);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ScheduleEligibilityPolicyService policies;

    @Test
    void reissuingTheRetiredLegacyVersionFromItsStartDateCoversExistingContracts() throws Exception {
        // 역할은 데모 V3만 넣으므로 이 DB에는 없다. 시험 트랜잭션과 함께 롤백된다.
        jdbc.update("INSERT INTO fgc.app_role(role_code, role_name) VALUES ('SETTLEMENT', '정산'), ('GA_ADMIN', 'GA관리자')");
        FgcUserDetails writer = insertUser("SETTLEMENT");
        FgcUserDetails approver = insertUser("GA_ADMIN");
        Long legacy = jdbc.queryForObject("""
                SELECT policy_version_id FROM fgc.policy_version
                 WHERE policy_code = 'STATUS-MONTH-RULE-2026' AND version_no = 1 AND status = 'RETIRED'
                """, Long.class);
        assertThatThrownBy(() -> policies.resolve(EXISTING_CONTRACT)).isInstanceOf(FgcBusinessException.class);

        JsonNode cloned = send(HttpMethod.POST, "/api/v1/policies/" + legacy + "/versions", writer,
                mapper.createObjectNode().put("effectiveFrom", "2026-01-01").put("reason", "유산 v1 재발행"), 201);
        long id = cloned.path("data").path("policyVersionId").asLong();
        ObjectNode edit = detail(id, writer).path("editDocument").deepCopy();
        edit.set("parameters", parameters());

        JsonNode rejected = send(HttpMethod.PUT, "/api/v1/policies/" + id, writer, edit, 400);
        assertThat(rejected.path("error").path("field").asText()).isEqualTo("sourceRefs");

        edit.putArray("sourceRefs"); // regulationRefs의 REG-03·REG-13(V71 레지스트리)은 남겨 제출 근거로 쓴다
        JsonNode saved = send(HttpMethod.PUT, "/api/v1/policies/" + id, writer, edit, 200).path("data");
        JsonNode review = action(id, "SUBMIT", saved, writer);
        JsonNode approved = action(id, "APPROVE", review, approver);
        action(id, "ACTIVATE", approved, approver);

        assertThat(policies.resolve(EXISTING_CONTRACT)).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.policy_succession", Long.class)).isZero();
    }

    private ArrayNode parameters() {
        ArrayNode parameters = mapper.createArrayNode();
        parameters.addObject().put("key", "STATUS_MONTH_RULE").put("value", "PAY_IF_ACTIVE_ON_DUE_DATE");
        parameters.addObject().put("key", "PRORATION_RULE").put("value", "NO_PRORATION");
        parameters.addObject().put("key", "REVIVAL_BACKFILL_RULE").put("value", "NO_RETROACTIVE");
        return parameters;
    }

    private JsonNode action(long id, String action, JsonNode version, FgcUserDetails actor) throws Exception {
        ObjectNode payload = mapper.createObjectNode().put("actionType", action).put("reason", "유산 v1 재발행")
                .put("expectedStatus", version.path("status").asText())
                .put("expectedUpdatedAt", version.path("updatedAt").asText());
        return send(HttpMethod.POST, "/api/v1/policies/" + id + "/actions", actor, payload, 200).path("data");
    }

    private JsonNode detail(long id, FgcUserDetails actor) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/policies/" + id).with(user(actor))).andReturn();
        return mapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private JsonNode send(HttpMethod method, String path, FgcUserDetails actor, JsonNode payload, int expected)
            throws Exception {
        MvcResult result = mvc.perform(request(method, path).with(user(actor)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(payload)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).withFailMessage(result.getResponse().getContentAsString())
                .isEqualTo(expected);
        return mapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private FgcUserDetails insertUser(String role) {
        String login = "p648-" + UUID.randomUUID();
        Long userId = jdbc.queryForObject("""
                INSERT INTO fgc.app_user(login_id, password_hash, user_name, role_id)
                VALUES (?, 'unused', 'FUN-015 시험 사용자', (SELECT role_id FROM fgc.app_role WHERE role_code = ?))
                RETURNING user_id
                """, Long.class, login, role);
        return new FgcUserDetails(new AppUserView(userId, login, "unused", "FUN-015 시험 사용자", role, "ACTIVE"), true, true);
    }
}
