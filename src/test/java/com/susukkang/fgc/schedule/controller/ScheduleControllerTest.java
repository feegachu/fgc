package com.susukkang.fgc.schedule.controller;

import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.ScheduleHeaderStatus;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.schedule.dto.ScheduleRegenResponse;
import com.susukkang.fgc.schedule.dto.ScheduleDetailResponse;
import com.susukkang.fgc.schedule.dto.ScheduleHeaderResponse;
import com.susukkang.fgc.schedule.dto.ScheduleLineResponse;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    void returnsKoreanLabelsWithScheduleListCodes() throws Exception {
        ScheduleHeaderResponse header = scheduleHeaderWithCodes();
        when(scheduleService.selectByCondition(any(), eq(1), eq(20)))
                .thenReturn(PageResponse.of(java.util.List.of(header), 1, 20, 1, "scheduleHeaderId,desc"));

        mockMvc.perform(get("/api/v1/schedules")
                        .with(user("viewer").roles("COMPLIANCE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].paymentStage").value("GA_TO_FC"))
                .andExpect(jsonPath("$.data.content[0].paymentStageLabel").value("GA→설계사"))
                .andExpect(jsonPath("$.data.content[0].scheduleRegime").value("FOUR_YEAR_2027"))
                .andExpect(jsonPath("$.data.content[0].scheduleRegimeLabel").value("4년 분급(2027)"))
                .andExpect(jsonPath("$.data.content[0].schedulePurpose").value("COMPARISON"))
                .andExpect(jsonPath("$.data.content[0].schedulePurposeLabel").value("비교"))
                .andExpect(jsonPath("$.data.content[0].status").value("ADJUSTED"))
                .andExpect(jsonPath("$.data.content[0].statusLabel").value("조정"));
    }

    @Test
    void returnsKoreanLabelsWithScheduleDetailCodes() throws Exception {
        when(scheduleService.selectScheduleDetailById(10L)).thenReturn(
                ScheduleDetailResponse.builder()
                        .header(scheduleHeaderWithCodes())
                        .lines(java.util.List.of())
                        .build());

        mockMvc.perform(get("/api/v1/schedules/{id}", 10L)
                        .with(user("viewer").roles("COMPLIANCE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.header.paymentStageLabel").value("GA→설계사"))
                .andExpect(jsonPath("$.data.header.scheduleRegimeLabel").value("4년 분급(2027)"))
                .andExpect(jsonPath("$.data.header.schedulePurposeLabel").value("비교"))
                .andExpect(jsonPath("$.data.header.statusLabel").value("조정"));
    }

    @Test
    void returnsScheduleVersionsForSameContractAndStage() throws Exception {
        when(scheduleService.selectScheduleVersions(10L)).thenReturn(java.util.List.of(
                ScheduleHeaderResponse.builder()
                        .scheduleHeaderId(11L)
                        .paymentStage(PaymentStage.INSURER_TO_GA)
                        .scheduleRegime("SEVEN_YEAR_2029")
                        .schedulePurpose("SIMULATION")
                        .scheduleVersionNo(2)
                        .status(ScheduleHeaderStatus.RESTARTED)
                        .activeYn(true)
                        .build(),
                ScheduleHeaderResponse.builder()
                        .scheduleHeaderId(10L)
                        .scheduleVersionNo(1)
                        .activeYn(false)
                        .build()
        ));

        mockMvc.perform(get("/api/v1/schedules/{id}/versions", 10L)
                        .with(user("viewer").roles("COMPLIANCE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].scheduleHeaderId").value(11))
                .andExpect(jsonPath("$.data[0].paymentStageLabel").value("원수사→GA"))
                .andExpect(jsonPath("$.data[0].scheduleRegimeLabel").value("7년 분급(2029)"))
                .andExpect(jsonPath("$.data[0].schedulePurposeLabel").value("시뮬레이션"))
                .andExpect(jsonPath("$.data[0].statusLabel").value("재개"))
                .andExpect(jsonPath("$.data[1].scheduleVersionNo").value(1));

        verify(scheduleService).selectScheduleVersions(10L);
    }

    @Test
    void confirmsPlannedSchedule() throws Exception {
        when(scheduleService.confirmSchedule(10L)).thenReturn(
                ScheduleDetailResponse.builder()
                        .header(ScheduleHeaderResponse.builder()
                                .scheduleHeaderId(10L)
                                .status(com.susukkang.fgc.common.code.ScheduleHeaderStatus.CONFIRMED)
                                .build())
                        .lines(java.util.List.of(ScheduleLineResponse.builder()
                                .lineNo(1)
                                .lineStatus("CONFIRMED")
                                .build()))
                        .build()
        );

        mockMvc.perform(post("/api/v1/schedules/{id}/confirm", 10L)
                        .with(user("settlement").roles("SETTLEMENT")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.header.scheduleHeaderId").value(10))
                .andExpect(jsonPath("$.data.header.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.lines[0].lineNo").value(1))
                .andExpect(jsonPath("$.data.lines[0].lineStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.schedules").doesNotExist());

        verify(scheduleService).confirmSchedule(10L);
    }

    @Test
    void rejectsConfirmationWithoutSettlementRole() throws Exception {
        mockMvc.perform(post("/api/v1/schedules/{id}/confirm", 10L)
                        .with(user("admin").roles("GA_ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void regeneratesScheduleWithReason() throws Exception {
        when(scheduleService.regenerateSchedules(10L, "정책 변경 반영")).thenReturn(ScheduleRegenResponse.builder().scheduleHeaderId(11L).versionNo(2L).build());

        mockMvc.perform(post("/api/v1/schedules/{id}/regenerate", 10L).with(user("settlement").roles("SETTLEMENT")).with(csrf()).contentType(APPLICATION_JSON).content("{\"reason\":\"정책 변경 반영\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scheduleHeaderId").value(11))
                .andExpect(jsonPath("$.data.versionNo").value(2));

        verify(scheduleService).regenerateSchedules(10L, "정책 변경 반영");
    }

    @Test
    void rejectsBlankOrTooLongReason() throws Exception {
        mockMvc.perform(post("/api/v1/schedules/{id}/regenerate", 10L).with(user("settlement").roles("SETTLEMENT")).with(csrf()).contentType(APPLICATION_JSON).content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/schedules/{id}/regenerate", 10L).with(user("settlement").roles("SETTLEMENT")).with(csrf()).contentType(APPLICATION_JSON).content("{\"reason\":\"" + "가".repeat(41) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void acceptsReasonAtMaximumLength() throws Exception {
        String reason = "가".repeat(40);
        when(scheduleService.regenerateSchedules(10L, reason)).thenReturn(ScheduleRegenResponse.builder().scheduleHeaderId(11L).versionNo(2L).build());

        mockMvc.perform(post("/api/v1/schedules/{id}/regenerate", 10L).with(user("settlement").roles("SETTLEMENT")).with(csrf()).contentType(APPLICATION_JSON).content("{\"reason\":\"" + reason + "\"}"))
                .andExpect(status().isOk());

        verify(scheduleService).regenerateSchedules(10L, reason);
    }

    @Test
    void rejectsRegenerationWithoutSettlementRole() throws Exception {
        mockMvc.perform(post("/api/v1/schedules/{id}/regenerate", 10L).with(user("admin").roles("GA_ADMIN")).contentType(APPLICATION_JSON).content("{\"reason\":\"정책 변경 반영\"}"))
                .andExpect(status().isForbidden());
    }

    /** FUN-002(#82) — COMPLIANCE는 §4-1 "조회만"이라 재생성도 403이어야 한다. */
    @Test
    void rejectsRegenerationForComplianceRole() throws Exception {
        mockMvc.perform(post("/api/v1/schedules/{id}/regenerate", 10L).with(user("comp01").roles("COMPLIANCE")).contentType(APPLICATION_JSON).content("{\"reason\":\"정책 변경 반영\"}"))
                .andExpect(status().isForbidden());
    }

    /** FGC-FUN-002 — SYSTEM_ADMIN 은 "전부"(화면정의서 §4-1)라 재생성도 허용된다. */
    @Test
    void allowsRegenerationForSystemAdmin() throws Exception {
        when(scheduleService.regenerateSchedules(10L, "정책 변경 반영")).thenReturn(ScheduleRegenResponse.builder().scheduleHeaderId(11L).versionNo(2L).build());

        mockMvc.perform(post("/api/v1/schedules/{id}/regenerate", 10L).with(user("admin").roles("SYSTEM_ADMIN")).with(csrf()).contentType(APPLICATION_JSON).content("{\"reason\":\"정책 변경 반영\"}"))
                .andExpect(status().isOk());
    }

    private ScheduleHeaderResponse scheduleHeaderWithCodes() {
        return ScheduleHeaderResponse.builder()
                .scheduleHeaderId(10L)
                .paymentStage(PaymentStage.GA_TO_FC)
                .scheduleRegime("FOUR_YEAR_2027")
                .schedulePurpose("COMPARISON")
                .status(ScheduleHeaderStatus.ADJUSTED)
                .build();
    }
}
