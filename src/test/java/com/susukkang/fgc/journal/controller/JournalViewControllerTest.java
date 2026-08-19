package com.susukkang.fgc.journal.controller;

import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.common.web.ShellAdvice;
import com.susukkang.fgc.journal.dto.JournalListRow;
import com.susukkang.fgc.journal.dto.JournalSearchCriteria;
import com.susukkang.fgc.journal.service.JournalSearchService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 설명 : LEDG-W01 검증원장 MPA 조회 테스트
 *
 * @author yslee
 * @since 2026-08-19
 * @version 1.2
 */
@WebMvcTest(JournalViewController.class)
@Import({JournalViewController.class, ShellAdvice.class, SecurityConfig.class,
        MessageSourceAutoConfiguration.class, FgcMessageResolver.class, ConstraintErrorCodeResolver.class})
@TestPropertySource(properties = "fgc.demo-month=2026-07")
class JournalViewControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JournalSearchService journalSearchService;

    @Test
    void initialPageDoesNotSearchUntilButtonSubmission() throws Exception {
        mvc.perform(get("/journals").with(user(userWithRole("SETTLEMENT"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("조회 조건을 설정하고 조회 버튼을 눌러 주세요.")));

        verify(journalSearchService, never()).search(any(), eq(1), eq(20));
    }

    @Test
    void submittedConditionsRenderSearchResultOnServer() throws Exception {
        JournalListRow row = journalRow();
        given(journalSearchService.search(any(), eq(1), eq(20)))
                .willReturn(PageResponse.of(List.of(row), 1, 20, 1, "journalDate,desc"));

        mvc.perform(get("/journals")
                        .param("searched", "true")
                        .param("from", "2026-07-01")
                        .param("to", "2026-07-31")
                        .param("type", "CONFIRMED_FC_PAYOUT")
                        .param("status", "POSTED")
                        .with(user(userWithRole("SETTLEMENT"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("JRN-202607-001")))
                .andExpect(content().string(containsString("COMMISSION_TRANSACTION #91")))
                .andExpect(content().string(containsString("원분개 JRN-202607-000")));

        var captor = org.mockito.ArgumentCaptor.forClass(JournalSearchCriteria.class);
        verify(journalSearchService).search(captor.capture(), eq(1), eq(20));
        assertThat(captor.getValue().from()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(captor.getValue().status()).isEqualTo("POSTED");
    }

    @ParameterizedTest(name = "{0} LEDG-W01 역분개 가능={1}")
    @CsvSource({
            "SETTLEMENT,   true",
            "GA_ADMIN,     true",
            "SYSTEM_ADMIN, true",
            "COMPLIANCE,   false",
    })
    void reverseCapabilityMatchesExistingApiRoles(String role, boolean allowed) throws Exception {
        mvc.perform(get("/journals").with(user(userWithRole(role))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-can-reverse=\"" + allowed + "\"")));
    }

    private JournalListRow journalRow() {
        JournalListRow row = new JournalListRow();
        row.setJournalHeaderId(92L);
        row.setJournalNo("JRN-202607-001");
        row.setJournalDate(LocalDate.of(2026, 7, 31));
        row.setJournalType("CONFIRMED_FC_PAYOUT");
        row.setSourceEntityType("COMMISSION_TRANSACTION");
        row.setSourceEntityId("91");
        row.setContractId(7L);
        row.setContractNo("CONT-007");
        row.setStatus("POSTED");
        row.setDebitTotal(BigDecimal.valueOf(1000));
        row.setCreditTotal(BigDecimal.valueOf(1000));
        row.setReversalOfId(90L);
        row.setReversalOfJournalNo("JRN-202607-000");
        return row;
    }

    private FgcUserDetails userWithRole(String role) {
        AppUserView view = new AppUserView();
        view.setUserId(1L);
        view.setLoginId("ledger01");
        view.setPasswordHash("x");
        view.setUserName("원장조회자");
        view.setRoleCode(role);
        return new FgcUserDetails(view, true, true);
    }
}
