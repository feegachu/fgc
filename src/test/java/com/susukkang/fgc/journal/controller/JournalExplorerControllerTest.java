package com.susukkang.fgc.journal.controller;

import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.common.exception.*;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.journal.dto.JournalExplorer;
import com.susukkang.fgc.journal.repository.JournalExplorerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(JournalExplorerController.class)
@Import({GlobalExceptionHandler.class, FgcMessageResolver.class, ConstraintErrorCodeResolver.class,
        MessageSourceAutoConfiguration.class, SecurityConfig.class})
class JournalExplorerControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean JournalExplorerRepository repository;
    @Test void returnsPaginatedGroupsForInternalReaders() throws Exception {
        given(repository.explore(any(), isNull(), eq(false), isNull(), eq(1), eq(1), eq(20)))
                .willReturn(new JournalExplorer(PageResponse.of(List.of(), 1, 20, 0, "contractNo,asc"),
                        PageResponse.of(List.of(), 1, 20, 0, "journalDate,desc"), null));
        mvc.perform(get("/api/v1/journals/explorer").with(user("reader").roles("COMPLIANCE")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.contracts.content").isArray())
                .andExpect(jsonPath("$.data.journals.totalElements").value(0));
    }
    @Test void deniesAgentAndAnonymous() throws Exception {
        mvc.perform(get("/api/v1/journals/explorer")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/journals/explorer").with(user("fc").roles("AGENT"))).andExpect(status().isForbidden());
        verifyNoInteractions(repository);
    }
    @Test void rejectsInvalidPaginationIdsAndDateRangeBeforeQuerying() throws Exception {
        for (String query : List.of("?size=101", "?page=0", "?contractId=-1", "?journalId=0", "?from=2026-11-30&to=2026-11-01"))
            mvc.perform(get("/api/v1/journals/explorer" + query).with(user("settle").roles("SETTLEMENT")))
                    .andExpect(status().isBadRequest());
        verifyNoInteractions(repository);
    }
}
