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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

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
    void initialEntrySearchesBaseMonthRangeWithoutButtonSubmission() throws Exception {
        given(journalSearchService.search(any(), eq(1), eq(20)))
                .willReturn(PageResponse.of(List.of(), 1, 20, 0, "journalDate,desc"));

        mvc.perform(get("/journals").with(user(userWithRole("SETTLEMENT"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"2026-07-01\"")))
                .andExpect(content().string(containsString("value=\"2026-07-31\"")));

        var captor = org.mockito.ArgumentCaptor.forClass(JournalSearchCriteria.class);
        verify(journalSearchService).search(captor.capture(), eq(1), eq(20));
        assertThat(captor.getValue().from()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(captor.getValue().to()).isEqualTo(LocalDate.of(2026, 7, 31));
    }

    @Test
    void buttonSubmissionWithClearedDatesSearchesWithoutPeriodLimit() throws Exception {
        given(journalSearchService.search(any(), eq(1), eq(20)))
                .willReturn(PageResponse.of(List.of(), 1, 20, 0, "journalDate,desc"));

        mvc.perform(get("/journals").param("searched", "true").with(user(userWithRole("SETTLEMENT"))))
                .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(JournalSearchCriteria.class);
        verify(journalSearchService).search(captor.capture(), eq(1), eq(20));
        assertThat(captor.getValue().from()).isNull();
        assertThat(captor.getValue().to()).isNull();
    }

    @Test
    void explicitSearchedFalseDoesNotSearch() throws Exception {
        mvc.perform(get("/journals").param("searched", "false").with(user(userWithRole("SETTLEMENT"))))
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
                .andExpect(content().string(containsString("지급거래 #91")))
                .andExpect(content().string(containsString("원분개 JRN-202607-000")));

        var captor = org.mockito.ArgumentCaptor.forClass(JournalSearchCriteria.class);
        verify(journalSearchService).search(captor.capture(), eq(1), eq(20));
        assertThat(captor.getValue().from()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(captor.getValue().status()).isEqualTo("POSTED");
    }

    @Test
    void contractNoConditionIsTrimmedAndPassedToSearchAndRenderedInFilter() throws Exception {
        given(journalSearchService.search(any(), eq(1), eq(20)))
                .willReturn(PageResponse.of(List.of(), 1, 20, 0, "journalDate,desc"));

        mvc.perform(get("/journals")
                        .param("searched", "true")
                        .param("contractNo", "  fgc-fgl02-202608  ")
                        .with(user(userWithRole("SETTLEMENT"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"contractNo\"")))
                .andExpect(content().string(containsString("value=\"fgc-fgl02-202608\"")));

        var captor = org.mockito.ArgumentCaptor.forClass(JournalSearchCriteria.class);
        verify(journalSearchService).search(captor.capture(), eq(1), eq(20));
        assertThat(captor.getValue().contractNo()).isEqualTo("fgc-fgl02-202608");
    }

    @Test
    void contractNoOfEightyCharsIsSearchedAsIsAndEightyOneIsRejectedWithoutSearch() throws Exception {
        given(journalSearchService.search(any(), eq(1), eq(20)))
                .willReturn(PageResponse.of(List.of(), 1, 20, 0, "journalDate,desc"));
        String eighty = "A".repeat(80);

        mvc.perform(get("/journals").param("searched", "true").param("contractNo", eighty)
                        .with(user(userWithRole("SETTLEMENT"))))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("80자 이하로 입력해 주세요"))));
        var captor = org.mockito.ArgumentCaptor.forClass(JournalSearchCriteria.class);
        verify(journalSearchService).search(captor.capture(), eq(1), eq(20));
        assertThat(captor.getValue().contractNo()).isEqualTo(eighty);

        mvc.perform(get("/journals").param("searched", "true").param("contractNo", eighty + "A")
                        .with(user(userWithRole("SETTLEMENT"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("계약번호는 80자 이하로 입력해 주세요.")));
        // 81자 요청은 앞 80자로 잘라 검색하지 않는다(위 80자 검색 1회만 호출된 상태 유지).
        verify(journalSearchService, org.mockito.Mockito.times(1)).search(any(), eq(1), eq(20));
    }

    @ParameterizedTest(name = "{0} LEDG-W01 역분개 가능={1}")
    @CsvSource({
            "SETTLEMENT,   true",
            "GA_ADMIN,     true",
            "SYSTEM_ADMIN, true",
            "COMPLIANCE,   false",
    })
    void reverseCapabilityMatchesExistingApiRoles(String role, boolean allowed) throws Exception {
        given(journalSearchService.search(any(), eq(1), eq(20)))
                .willReturn(PageResponse.of(List.of(), 1, 20, 0, "journalDate,desc"));
        mvc.perform(get("/journals").with(user(userWithRole(role))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-can-reverse=\"" + allowed + "\"")));
    }

    @Test
    void negativeDebitAndCreditRenderWithMinusAndRedAmountClass() throws Exception {
        JournalListRow row = journalRow();
        row.setDebitTotal(BigDecimal.valueOf(-1517944));
        row.setCreditTotal(BigDecimal.valueOf(-1517944));
        given(journalSearchService.search(any(), eq(1), eq(20)))
                .willReturn(PageResponse.of(List.of(row), 1, 20, 1, "journalDate,desc"));

        String html = mvc.perform(get("/journals").with(user(userWithRole("SETTLEMENT"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Pattern negativeCell = Pattern.compile(
                "<td\\b[^>]*class=\"[^\"]*\\bis-negative-amount\\b[^\"]*\"[^>]*>\\s*-1,517,944원\\s*</td>");
        assertThat(negativeCell.matcher(html).results().count()).isEqualTo(2);
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
