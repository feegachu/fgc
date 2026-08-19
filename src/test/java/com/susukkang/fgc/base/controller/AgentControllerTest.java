package com.susukkang.fgc.base.controller;

import com.susukkang.fgc.base.dto.AgentResponse;
import com.susukkang.fgc.base.dto.AgentSearchCriteria;
import com.susukkang.fgc.base.service.AgentService;
import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import com.susukkang.fgc.common.web.PageResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentController.class)
@Import({AgentController.class, GlobalExceptionHandler.class, SecurityConfig.class})
class AgentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AgentService agentService;

    @MockitoBean
    private FgcMessageResolver messageResolver;

    @MockitoBean
    private ConstraintErrorCodeResolver constraintErrorCodeResolver;

    @Test
    void returnsPagedAgentsForBaseAndContractScreens() throws Exception {
        LocalDate asOf = LocalDate.of(2026, 8, 11);
        AgentSearchCriteria criteria = new AgentSearchCriteria(11L, "김설계", asOf);
        AgentResponse agent = new AgentResponse(
                101L,
                "FC-SEOUL-001",
                "김설계",
                "FC",
                "설계사",
                11L,
                "FGC-BR-SEOUL",
                "서울지사",
                LocalDate.of(2026, 5, 1),
                null,
                "ACTIVE",
                "활동",
                LocalDate.of(2026, 5, 1),
                false,
                LocalDate.of(2026, 5, 1),
                true,
                LocalDate.of(2027, 4, 30),
                true
        );
        given(agentService.search(criteria, 1, 20)).willReturn(
                PageResponse.of(List.of(agent), 1, 20, 1, "agentCode,asc")
        );

        mockMvc.perform(get("/api/v1/base/agents")
                        .param("organizationId", "11")
                        .param("keyword", "김설계")
                        .param("asOf", "2026-08-11")
                        .with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].agentId").value(101))
                .andExpect(jsonPath("$.data.content[0].agentCode").value("FC-SEOUL-001"))
                .andExpect(jsonPath("$.data.content[0].agentName").value("김설계"))
                .andExpect(jsonPath("$.data.content[0].rankCode").value("FC"))
                .andExpect(jsonPath("$.data.content[0].rankLabel").value("설계사"))
                .andExpect(jsonPath("$.data.content[0].organizationId").value(11))
                .andExpect(jsonPath("$.data.content[0].organizationCode").value("FGC-BR-SEOUL"))
                .andExpect(jsonPath("$.data.content[0].organizationName").value("서울지사"))
                .andExpect(jsonPath("$.data.content[0].appointmentDate").value("2026-05-01"))
                .andExpect(jsonPath("$.data.content[0].terminationDate").isEmpty())
                .andExpect(jsonPath("$.data.content[0].agentStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.content[0].agentStatusLabel").value("활동"))
                .andExpect(jsonPath("$.data.content[0].latestRegistrationDate").value("2026-05-01"))
                .andExpect(jsonPath("$.data.content[0].priorThreeYearExperienceYn").value(false))
                .andExpect(jsonPath("$.data.content[0].experienceCheckedOn").value("2026-05-01"))
                .andExpect(jsonPath("$.data.content[0].newcomerSupportEligibleYn").value(true))
                .andExpect(jsonPath("$.data.content[0].newcomerSupportEndDate").value("2027-04-30"))
                .andExpect(jsonPath("$.data.content[0].activeYn").value(true))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.sort").value("agentCode,asc"))
                .andExpect(jsonPath("$.error").isEmpty())
                .andExpect(jsonPath("$.requestId", matchesPattern("^\\d{8}-[0-9a-f]{6}$")));

        verify(agentService).search(criteria, 1, 20);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "GA_ADMIN", "SETTLEMENT", "COMPLIANCE"})
    void allowsEveryAuthenticatedRole(String role) throws Exception {
        LocalDate asOf = LocalDate.of(2026, 8, 11);
        AgentSearchCriteria criteria = new AgentSearchCriteria(null, null, asOf);
        given(agentService.search(criteria, 2, 10)).willReturn(
                PageResponse.of(List.of(), 2, 10, 0, "agentCode,asc")
        );

        mockMvc.perform(get("/api/v1/base/agents")
                        .param("asOf", "2026-08-11")
                        .param("page", "2")
                        .param("size", "10")
                        .with(user("fgc-user").roles(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty());
    }

    @Test
    void rejectsAuthenticatedUserWithoutAllowedRole() throws Exception {
        mockMvc.perform(get("/api/v1/base/agents")
                        .param("asOf", "2026-08-11")
                        .with(user("other-user").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsInvalidAsOfFormat() throws Exception {
        mockMvc.perform(get("/api/v1/base/agents")
                        .param("asOf", "2026/08/11")
                        .with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"))
                .andExpect(jsonPath("$.error.field").value("asOf"));
    }

    @Test
    void rejectsMissingAsOf() throws Exception {
        mockMvc.perform(get("/api/v1/base/agents")
                        .with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"))
                .andExpect(jsonPath("$.error.field").value("asOf"));
    }

    @ParameterizedTest
    @CsvSource({"0, 20, page", "1, 101, size", "1, 20, organizationId"})
    void rejectsInvalidSearchValues(int page, int size, String field) throws Exception {
        LocalDate asOf = LocalDate.of(2026, 8, 11);
        Long organizationId = "organizationId".equals(field) ? 0L : null;
        AgentSearchCriteria criteria = new AgentSearchCriteria(organizationId, null, asOf);
        given(agentService.search(criteria, page, size)).willThrow(
                new FgcBusinessException(FgcErrorCode.COMMON_002, field, Map.of("field", field), null)
        );

        var request = get("/api/v1/base/agents")
                .param("asOf", "2026-08-11")
                .param("page", String.valueOf(page))
                .param("size", String.valueOf(size))
                .with(user("settle01").roles("SETTLEMENT"));
        if (organizationId != null) {
            request.param("organizationId", String.valueOf(organizationId));
        }

        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"))
                .andExpect(jsonPath("$.error.field").value(field));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/base/agents").param("asOf", "2026-08-11"))
                .andExpect(status().isUnauthorized());
    }
}
