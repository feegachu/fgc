package com.susukkang.fgc.contract.controller;

import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.common.web.ShellAdvice;
import com.susukkang.fgc.contract.domain.ContractStatus;
import com.susukkang.fgc.contract.domain.DataOrigin;
import com.susukkang.fgc.contract.dto.ContractSearchCondition;
import com.susukkang.fgc.contract.dto.ContractView;
import com.susukkang.fgc.contract.service.ContractService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.mockito.ArgumentCaptor;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(ContractViewController.class)
@Import({ContractViewController.class, ShellAdvice.class, SecurityConfig.class, GlobalExceptionHandler.class,
        FgcMessageResolver.class, ConstraintErrorCodeResolver.class, MessageSourceAutoConfiguration.class})
@TestPropertySource(properties = "fgc.demo-month=2026-07")
class ContractViewControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean ContractService contractService;

    private static FgcUserDetails userDetails() {
        return userDetails("SETTLEMENT");
    }

    private static FgcUserDetails userDetails(String role) {
        AppUserView user = new AppUserView();
        user.setUserId(1L); user.setLoginId("settle01"); user.setPasswordHash("x");
        user.setUserName("테스트 사용자"); user.setRoleCode(role); user.setAccountStatus("ACTIVE");
        return new FgcUserDetails(user, true, true);
    }

    @Test
    void 계약_등록_화면은_생성모드와_전용_스크립트를_렌더링한다() throws Exception {
        mockMvc.perform(get("/contracts/new").with(user(userDetails("SETTLEMENT"))))
                .andExpect(status().isOk())
                .andExpect(view().name("contract/form"))
                .andExpect(model().attribute("isEditMode", false))
                .andExpect(content().string(containsString("data-mode=\"create\"")))
                .andExpect(content().string(containsString("/js/features/contract/contract-api.js")))
                .andExpect(content().string(containsString("/js/features/contract/contract-form.js")));
    }

    @Test
    void 계약_수정_화면은_계약_ID를_화면에_전달한다() throws Exception {
        mockMvc.perform(get("/contracts/{id}/edit", 21L).with(user(userDetails("SETTLEMENT"))))
                .andExpect(status().isOk())
                .andExpect(view().name("contract/form"))
                .andExpect(model().attribute("isEditMode", true))
                .andExpect(model().attribute("contractId", 21L))
                .andExpect(content().string(containsString("data-mode=\"edit\"")))
                .andExpect(content().string(containsString("data-contract-id=\"21\"")));
    }

    @ParameterizedTest(name = "{0} 계약 폼 접근={1}")
    @CsvSource({
            "SETTLEMENT, 200",
            "SYSTEM_ADMIN, 200",
            "GA_ADMIN, 403",
            "COMPLIANCE, 403"
    })
    void 처리_권한이_있는_역할만_계약_폼에_접근한다(String role, int expectedStatus) throws Exception {
        mockMvc.perform(get("/contracts/new").with(user(userDetails(role))))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void 계약_검색조건을_서비스에_전달하고_실제_조회결과를_렌더링한다() throws Exception {
        ContractView row = ContractView.builder().contractId(10L).contractNo("C-2026-001")
                .insurerName("FG보험").productName("안심보험").contractDate(LocalDate.of(2026, 7, 3))
                .monthlyEquivalentFirstPremium(new BigDecimal("100000")).agentIdName("FC100 홍길동")
                .contractStatus(ContractStatus.ACTIVE).capResultStatus(CapResultStatus.NORMAL)
                .dataOrigin(DataOrigin.NORMALIZED_DB).build();
        given(contractService.selectByCondition(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(2), org.mockito.ArgumentMatchers.eq(20)))
                .willReturn(PageResponse.of(List.of(row), 2, 20, 21, "contractId,desc"));

        mockMvc.perform(get("/contracts").param("page", "2").param("contractNo", "C-2026")
                        .param("currentStatus", "ACTIVE").with(user(userDetails())))
                .andExpect(status().isOk()).andExpect(view().name("contract/list"))
                .andExpect(model().attributeExists("contracts", "condition"))
                .andExpect(content().string(containsString("FGC-UI-CONT-W01")))
                .andExpect(content().string(containsString("C-2026-001")))
                .andExpect(content().string(containsString("100,000원")))
                .andExpect(content().string(containsString("aria-current=\"page\"")))
                .andExpect(content().string(containsString(
                        "aria-label=\"다음 5페이지\" aria-disabled=\"true\" tabindex=\"-1\"")));

        ArgumentCaptor<ContractSearchCondition> captor = ArgumentCaptor.forClass(ContractSearchCondition.class);
        verify(contractService).selectByCondition(captor.capture(), org.mockito.ArgumentMatchers.eq(2), org.mockito.ArgumentMatchers.eq(20));
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getContractNo()).isEqualTo("C-2026");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getCurrentStatus()).isEqualTo(ContractStatus.ACTIVE);
    }

    @ParameterizedTest(name = "{0} 계약 등록 버튼 노출={1}")
    @CsvSource({
            "SETTLEMENT, true",
            "SYSTEM_ADMIN, true",
            "GA_ADMIN, false",
            "COMPLIANCE, false",
    })
    void 처리_권한이_있는_역할에만_계약_등록_버튼을_표시한다(String role, boolean visible) throws Exception {
        given(contractService.selectByCondition(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(20)
        )).willReturn(PageResponse.of(List.of(), 1, 20, 0, "contractId,desc"));

        var marker = containsString("data-fgc-action=\"create\"");
        mockMvc.perform(get("/contracts").with(user(userDetails(role))))
                .andExpect(status().isOk())
                .andExpect(content().string(visible ? marker : not(marker)));
    }
}
