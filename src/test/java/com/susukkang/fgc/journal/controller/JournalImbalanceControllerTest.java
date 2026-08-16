package com.susukkang.fgc.journal.controller;

import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import com.susukkang.fgc.journal.dto.JournalImbalanceSearchResponse;
import com.susukkang.fgc.journal.service.JournalImbalanceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * JournalImbalanceController(IF-API-37) API 통합테스트.
 * JournalImbalanceService는 mock으로 대체해 컨트롤러의 요청 파싱·인증/인가·응답 변환만 검증한다.
 */
@WebMvcTest(JournalImbalanceController.class)
@Import({JournalImbalanceController.class, GlobalExceptionHandler.class, FgcMessageResolver.class,
        ConstraintErrorCodeResolver.class, MessageSourceAutoConfiguration.class, SecurityConfig.class})
class JournalImbalanceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JournalImbalanceService journalImbalanceService;

    @Test
    // validationRunId 없이 호출하면 400, 서비스 호출 안 함
    void returns400WhenValidationRunIdIsMissing() throws Exception {
        mockMvc.perform(get("/api/v1/journals/imbalances").with(user("viewer01").roles("SETTLEMENT")))
                .andExpect(status().isBadRequest());

        verify(journalImbalanceService, never()).findImbalances(org.mockito.ArgumentMatchers.any());
    }

    @Test
    // 존재하지 않는 validationRunId — 서비스가 COMMON_004를 던지면 404로 변환
    void returns404WhenValidationRunDoesNotExist() throws Exception {
        given(journalImbalanceService.findImbalances(999_999_999L))
                .willThrow(new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("id", 999_999_999L)));

        mockMvc.perform(get("/api/v1/journals/imbalances")
                        .param("validationRunId", "999999999")
                        .with(user("viewer01").roles("SETTLEMENT")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-004"));
    }

    @Test
    // 불균형이 없으면 200 + 빈 목록 + totalCount 0 (에러 아님)
    void returnsEmptyContentWhenNoImbalance() throws Exception {
        given(journalImbalanceService.findImbalances(100L))
                .willReturn(new JournalImbalanceSearchResponse(0, List.of()));

        mockMvc.perform(get("/api/v1/journals/imbalances")
                        .param("validationRunId", "100")
                        .with(user("viewer01").roles("SETTLEMENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(0))
                .andExpect(jsonPath("$.data.content").isEmpty());
    }

    @Test
    // validationRunId가 그대로 서비스로 전달되는지, 결과가 그대로 응답에 실리는지
    void passesValidationRunIdAndReturnsServiceResult() throws Exception {
        given(journalImbalanceService.findImbalances(100L)).willReturn(new JournalImbalanceSearchResponse(0, List.of()));

        mockMvc.perform(get("/api/v1/journals/imbalances")
                        .param("validationRunId", "100")
                        .with(user("viewer01").roles("SETTLEMENT")))
                .andExpect(status().isOk());

        verify(journalImbalanceService).findImbalances(eq(100L));
    }

    @Test
    // 인증 없이 호출하면 401
    void returns401WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/journals/imbalances").param("validationRunId", "100"))
                .andExpect(status().isUnauthorized());
    }
}
