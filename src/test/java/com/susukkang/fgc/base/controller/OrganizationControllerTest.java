package com.susukkang.fgc.base.controller;

import com.susukkang.fgc.base.dto.OrganizationResponse;
import com.susukkang.fgc.base.dto.OrganizationSearchCriteria;
import com.susukkang.fgc.base.service.OrganizationService;
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

@WebMvcTest(OrganizationController.class)
@Import({OrganizationController.class, GlobalExceptionHandler.class, SecurityConfig.class})
class OrganizationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrganizationService organizationService;

    @MockitoBean
    private FgcMessageResolver messageResolver;

    @MockitoBean
    private ConstraintErrorCodeResolver constraintErrorCodeResolver;

    @Test
    void returnsPagedOrganizationsWithDefaultPaging() throws Exception {
        LocalDate asOf = LocalDate.of(2026, 8, 11);
        OrganizationSearchCriteria criteria = new OrganizationSearchCriteria("서울", asOf);
        OrganizationResponse organization = new OrganizationResponse(
                11L,
                "FGC-BR-SEOUL",
                "서울지사",
                "BRANCH",
                "지사",
                1L,
                "FGC 대표 GA",
                LocalDate.of(2026, 1, 1),
                null,
                true
        );
        given(organizationService.search(criteria, 1, 20)).willReturn(
                PageResponse.of(List.of(organization), 1, 20, 1, "organizationCode,asc")
        );

        mockMvc.perform(get("/api/v1/base/organizations")
                        .param("keyword", "서울")
                        .param("asOf", "2026-08-11")
                        .with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].organizationId").value(11))
                .andExpect(jsonPath("$.data.content[0].organizationCode").value("FGC-BR-SEOUL"))
                .andExpect(jsonPath("$.data.content[0].organizationName").value("서울지사"))
                .andExpect(jsonPath("$.data.content[0].organizationType").value("BRANCH"))
                .andExpect(jsonPath("$.data.content[0].organizationTypeLabel").value("지사"))
                .andExpect(jsonPath("$.data.content[0].parentId").value(1))
                .andExpect(jsonPath("$.data.content[0].parentName").value("FGC 대표 GA"))
                .andExpect(jsonPath("$.data.content[0].effectiveFrom").value("2026-01-01"))
                .andExpect(jsonPath("$.data.content[0].effectiveTo").isEmpty())
                .andExpect(jsonPath("$.data.content[0].activeYn").value(true))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.sort").value("organizationCode,asc"))
                .andExpect(jsonPath("$.error").isEmpty())
                .andExpect(jsonPath("$.requestId", matchesPattern("^\\d{8}-[0-9a-f]{6}$")));

        verify(organizationService).search(criteria, 1, 20);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "GA_ADMIN", "SETTLEMENT", "COMPLIANCE"})
    void allowsEveryAuthenticatedRole(String role) throws Exception {
        LocalDate asOf = LocalDate.of(2026, 8, 11);
        OrganizationSearchCriteria criteria = new OrganizationSearchCriteria(null, asOf);
        given(organizationService.search(criteria, 2, 10)).willReturn(
                PageResponse.of(List.of(), 2, 10, 0, "organizationCode,asc")
        );

        mockMvc.perform(get("/api/v1/base/organizations")
                        .param("asOf", "2026-08-11")
                        .param("page", "2")
                        .param("size", "10")
                        .with(user("fgc-user").roles(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.content").isEmpty());
    }

    @Test
    void rejectsAuthenticatedUserWithoutAllowedRole() throws Exception {
        mockMvc.perform(get("/api/v1/base/organizations")
                        .param("asOf", "2026-08-11")
                        .with(user("other-user").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsInvalidAsOfFormat() throws Exception {
        mockMvc.perform(get("/api/v1/base/organizations")
                        .param("asOf", "2026/08/11")
                        .with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"))
                .andExpect(jsonPath("$.error.field").value("asOf"));
    }

    @Test
    void rejectsMissingAsOf() throws Exception {
        mockMvc.perform(get("/api/v1/base/organizations")
                        .with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"))
                .andExpect(jsonPath("$.error.field").value("asOf"));
    }

    @ParameterizedTest
    @CsvSource({"0, 20, page", "1, 101, size"})
    void rejectsInvalidPaging(int page, int size, String field) throws Exception {
        LocalDate asOf = LocalDate.of(2026, 8, 11);
        OrganizationSearchCriteria criteria = new OrganizationSearchCriteria(null, asOf);
        given(organizationService.search(criteria, page, size)).willThrow(
                new FgcBusinessException(
                        FgcErrorCode.COMMON_002,
                        field,
                        Map.of("field", field),
                        null
                )
        );

        mockMvc.perform(get("/api/v1/base/organizations")
                        .param("asOf", "2026-08-11")
                        .param("page", String.valueOf(page))
                        .param("size", String.valueOf(size))
                        .with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"))
                .andExpect(jsonPath("$.error.field").value(field));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/base/organizations")
                        .param("asOf", "2026-08-11"))
                .andExpect(status().isUnauthorized());
    }
}
