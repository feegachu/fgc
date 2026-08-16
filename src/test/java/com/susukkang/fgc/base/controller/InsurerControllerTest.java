package com.susukkang.fgc.base.controller;

import com.susukkang.fgc.base.dto.InsurerResponse;
import com.susukkang.fgc.base.service.InsurerService;
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

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InsurerController.class)
@Import({InsurerController.class, GlobalExceptionHandler.class, SecurityConfig.class})
class InsurerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InsurerService insurerService;

    @MockitoBean
    private FgcMessageResolver messageResolver;

    @MockitoBean
    private ConstraintErrorCodeResolver constraintErrorCodeResolver;

    @Test
    void returnsPagedInsurersWithDefaultPaging() throws Exception {
        InsurerResponse insurer = new InsurerResponse(
                1L,
                "FGL01",
                "미래가상생명",
                "LIFE",
                "생명보험",
                true
        );
        given(insurerService.search("미래", 1, 20)).willReturn(
                PageResponse.of(List.of(insurer), 1, 20, 1, "insurerCode,asc")
        );

        mockMvc.perform(get("/api/v1/base/insurers")
                        .param("keyword", "미래")
                        .with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].insurerId").value(1))
                .andExpect(jsonPath("$.data.content[0].insurerCode").value("FGL01"))
                .andExpect(jsonPath("$.data.content[0].insurerName").value("미래가상생명"))
                .andExpect(jsonPath("$.data.content[0].insurerType").value("LIFE"))
                .andExpect(jsonPath("$.data.content[0].insurerTypeLabel").value("생명보험"))
                .andExpect(jsonPath("$.data.content[0].activeYn").value(true))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.sort").value("insurerCode,asc"))
                .andExpect(jsonPath("$.error").isEmpty())
                .andExpect(jsonPath("$.requestId", matchesPattern("^\\d{8}-[0-9a-f]{6}$")));

        verify(insurerService).search("미래", 1, 20);
    }

    @Test
    void includesInactiveInsurerWithActiveYnFalse() throws Exception {
        InsurerResponse inactive = new InsurerResponse(
                7L,
                "FGN09",
                "중지가상손해보험",
                "NON_LIFE",
                "손해보험",
                false
        );
        given(insurerService.search(null, 1, 20)).willReturn(
                PageResponse.of(List.of(inactive), 1, 20, 1, "insurerCode,asc")
        );

        mockMvc.perform(get("/api/v1/base/insurers")
                        .with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].insurerCode").value("FGN09"))
                .andExpect(jsonPath("$.data.content[0].insurerTypeLabel").value("손해보험"))
                .andExpect(jsonPath("$.data.content[0].activeYn").value(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "GA_ADMIN", "SETTLEMENT", "COMPLIANCE"})
    void allowsEveryAuthenticatedRole(String role) throws Exception {
        given(insurerService.search(null, 2, 10)).willReturn(
                PageResponse.of(List.of(), 2, 10, 0, "insurerCode,asc")
        );

        mockMvc.perform(get("/api/v1/base/insurers")
                        .param("page", "2")
                        .param("size", "10")
                        .with(user("fgc-user").roles(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.content").isEmpty());
    }

    @Test
    void rejectsAuthenticatedUserWithoutAllowedRole() throws Exception {
        mockMvc.perform(get("/api/v1/base/insurers")
                        .with(user("other-user").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @CsvSource({"0, 20, page", "1, 101, size"})
    void rejectsInvalidPaging(int page, int size, String field) throws Exception {
        given(insurerService.search(null, page, size)).willThrow(
                new FgcBusinessException(
                        FgcErrorCode.COMMON_002,
                        field,
                        Map.of("field", field),
                        null
                )
        );

        mockMvc.perform(get("/api/v1/base/insurers")
                        .param("page", String.valueOf(page))
                        .param("size", String.valueOf(size))
                        .with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"))
                .andExpect(jsonPath("$.error.field").value(field));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/base/insurers"))
                .andExpect(status().isUnauthorized());
    }
}
