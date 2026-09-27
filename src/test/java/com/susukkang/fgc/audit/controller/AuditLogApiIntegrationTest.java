package com.susukkang.fgc.audit.controller;

import com.susukkang.fgc.audit.entity.AuditLog;
import com.susukkang.fgc.audit.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 설명 : 실제 JPA 저장·조회와 IF-API-52 응답 직렬화를 함께 검증한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-26
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuditLogApiIntegrationTest {
    @Autowired
    private AuditLogRepository repository;
    @Autowired
    private MockMvc mvc;

    /**
     * 설명 : 배치 감사행과 빈 페이지의 API 응답 필드 및 전체 건수 계약을 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void returnsStoredBatchRowAndEmptyPageWithoutChangingResponseContract() throws Exception {
        String type = "API_" + UUID.randomUUID().toString().substring(0, 8);
        repository.saveAndFlush(AuditLog.create(null, "TEST_API", type, "42", null,
                "{\"nested\":{\"enabled\":true}}", null, "stored-request", null, null));

        mvc.perform(get("/api/v1/audit-logs").param("entityType", type).param("size", "1")
                        .with(user("audit01").roles("COMPLIANCE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].auditLogId").isNumber())
                .andExpect(jsonPath("$.data.content[0].occurredAt").isString())
                .andExpect(jsonPath("$.data.content[0].userId").isEmpty())
                .andExpect(jsonPath("$.data.content[0].userLoginId").isEmpty())
                .andExpect(jsonPath("$.data.content[0].beforeValue").isEmpty())
                .andExpect(jsonPath("$.data.content[0].afterValue").value("{\"nested\": {\"enabled\": true}}"))
                .andExpect(jsonPath("$.data.content[0].requestId").value("stored-request"))
                .andExpect(jsonPath("$.data.content[0].entityId").value("42"))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.sort").value("occurredAt,desc"));

        mvc.perform(get("/api/v1/audit-logs").param("entityType", type).param("size", "1").param("page", "2")
                        .with(user("audit01").roles("COMPLIANCE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    /**
     * 설명 : 잘못된 페이지와 조회 기간에 기존 API 오류 코드 및 필드가 반환되는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void realServiceKeepsPagingAndDateErrors() throws Exception {
        mvc.perform(get("/api/v1/audit-logs").param("page", "0")
                        .with(user("audit01").roles("COMPLIANCE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"))
                .andExpect(jsonPath("$.error.field").value("page"));
        mvc.perform(get("/api/v1/audit-logs").param("from", "2026-08-02").param("to", "2026-08-01")
                        .with(user("audit01").roles("COMPLIANCE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"))
                .andExpect(jsonPath("$.error.field").value("from"));
    }
}
