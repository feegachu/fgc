package com.susukkang.fgc.contract.controller;

import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.code.CapResultStatus;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(ContractViewController.class)
@Import({ContractViewController.class, ShellAdvice.class, GlobalExceptionHandler.class,
        FgcMessageResolver.class, ConstraintErrorCodeResolver.class, MessageSourceAutoConfiguration.class})
@TestPropertySource(properties = "fgc.demo-month=2026-07")
class ContractViewControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean ContractService contractService;

    private static FgcUserDetails userDetails() {
        AppUserView user = new AppUserView();
        user.setUserId(1L); user.setLoginId("settle01"); user.setPasswordHash("x");
        user.setUserName("정산담당"); user.setRoleCode("SETTLEMENT"); user.setAccountStatus("ACTIVE");
        return new FgcUserDetails(user, true, true);
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
                .andExpect(content().string(containsString("100,000원")));

        ArgumentCaptor<ContractSearchCondition> captor = ArgumentCaptor.forClass(ContractSearchCondition.class);
        verify(contractService).selectByCondition(captor.capture(), org.mockito.ArgumentMatchers.eq(2), org.mockito.ArgumentMatchers.eq(20));
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getContractNo()).isEqualTo("C-2026");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getCurrentStatus()).isEqualTo(ContractStatus.ACTIVE);
    }
}
