package com.susukkang.fgc.journal.controller;

import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import com.susukkang.fgc.journal.dto.JournalAccountRow;
import com.susukkang.fgc.journal.service.JournalAccountCatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/v1/journals/accounts — 활성 계정과목 목록의 인증과 응답 변환을 검증한다. */
@WebMvcTest(JournalAccountController.class)
@Import({JournalAccountController.class, GlobalExceptionHandler.class, FgcMessageResolver.class,
        ConstraintErrorCodeResolver.class, MessageSourceAutoConfiguration.class, SecurityConfig.class})
class JournalAccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JournalAccountCatalogService journalAccountCatalogService;

    @Test
    void returnsActiveAccountsForAuthenticatedUser() throws Exception {
        JournalAccountRow row = new JournalAccountRow();
        row.setJournalAccountId(1L);
        row.setAccountCode("1100");
        row.setAccountName("미수수수료");
        row.setNormalBalance("DEBIT");
        row.setActiveYn(true);
        given(journalAccountCatalogService.findAllActive()).willReturn(List.of(row));

        mockMvc.perform(get("/api/v1/journals/accounts").with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].accountCode").value("1100"))
                .andExpect(jsonPath("$.data[0].accountName").value("미수수수료"))
                .andExpect(jsonPath("$.data[0].normalBalance").value("DEBIT"));
    }

    @Test
    void rejectsUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/v1/journals/accounts")).andExpect(status().isUnauthorized());
    }
}
