package com.susukkang.fgc.schedule.controller;

import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import com.susukkang.fgc.schedule.dto.ScheduleRegenResponse;
import com.susukkang.fgc.schedule.service.ScheduleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ScheduleController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, FgcMessageResolver.class, ConstraintErrorCodeResolver.class, MessageSourceAutoConfiguration.class})
class ScheduleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ScheduleService scheduleService;

    @Test
    void regeneratesScheduleWithReason() throws Exception {
        when(scheduleService.regenerateSchedules(10L, "정책 변경 반영")).thenReturn(ScheduleRegenResponse.builder().scheduleHeaderId(11L).versionNo(2L).build());

        mockMvc.perform(post("/api/v1/schedules/{id}/regenerate", 10L).with(user("settlement").roles("SETTLEMENT")).contentType(APPLICATION_JSON).content("{\"reason\":\"정책 변경 반영\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scheduleHeaderId").value(11))
                .andExpect(jsonPath("$.data.versionNo").value(2));

        verify(scheduleService).regenerateSchedules(10L, "정책 변경 반영");
    }

    @Test
    void rejectsBlankOrTooLongReason() throws Exception {
        mockMvc.perform(post("/api/v1/schedules/{id}/regenerate", 10L).with(user("settlement").roles("SETTLEMENT")).contentType(APPLICATION_JSON).content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/schedules/{id}/regenerate", 10L).with(user("settlement").roles("SETTLEMENT")).contentType(APPLICATION_JSON).content("{\"reason\":\"" + "가".repeat(41) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void acceptsReasonAtMaximumLength() throws Exception {
        String reason = "가".repeat(40);
        when(scheduleService.regenerateSchedules(10L, reason)).thenReturn(ScheduleRegenResponse.builder().scheduleHeaderId(11L).versionNo(2L).build());

        mockMvc.perform(post("/api/v1/schedules/{id}/regenerate", 10L).with(user("settlement").roles("SETTLEMENT")).contentType(APPLICATION_JSON).content("{\"reason\":\"" + reason + "\"}"))
                .andExpect(status().isOk());

        verify(scheduleService).regenerateSchedules(10L, reason);
    }

    @Test
    void rejectsRegenerationWithoutSettlementRole() throws Exception {
        mockMvc.perform(post("/api/v1/schedules/{id}/regenerate", 10L).with(user("admin").roles("GA_ADMIN")).contentType(APPLICATION_JSON).content("{\"reason\":\"정책 변경 반영\"}"))
                .andExpect(status().isForbidden());
    }

    /** FGC-FUN-002 — SYSTEM_ADMIN 은 "전부"(화면정의서 §4-1)라 재생성도 허용된다. */
    @Test
    void allowsRegenerationForSystemAdmin() throws Exception {
        when(scheduleService.regenerateSchedules(10L, "정책 변경 반영")).thenReturn(ScheduleRegenResponse.builder().scheduleHeaderId(11L).versionNo(2L).build());

        mockMvc.perform(post("/api/v1/schedules/{id}/regenerate", 10L).with(user("admin").roles("SYSTEM_ADMIN")).contentType(APPLICATION_JSON).content("{\"reason\":\"정책 변경 반영\"}"))
                .andExpect(status().isOk());
    }
}
