package com.susukkang.fgc.exceptioncase.controller;

import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.common.exception.*;
import com.susukkang.fgc.exceptioncase.dto.ExceptionPaymentCorrectionRequest;
import com.susukkang.fgc.exceptioncase.service.ExceptionPaymentCorrectionService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ExceptionPaymentCorrectionController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, FgcMessageResolver.class,
        ConstraintErrorCodeResolver.class, MessageSourceAutoConfiguration.class})
class ExceptionPaymentCorrectionControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ExceptionPaymentCorrectionService service;
    private static final String PATH = "/api/v1/exceptions/18/payment-correction";
    private static final String BODY = """
            {"paymentId":71,"expectedUpdatedAt":"2026-07-01T10:00:00+09:00","payment":{
              "sourceType":"GA_MANUAL_PAYMENT","sourceBusinessKey":"INS-71","contractId":3,
              "commissionItemId":1,"amount":800000,"settlementMonth":"2026-07-01",
              "cashflowType":"PAYMENT","scheduledPaymentDate":"2026-07-01","paymentStage":"INSURER_TO_GA","attributions":[]}}
            """;

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "SETTLEMENT", "GA_ADMIN", "COMPLIANCE", "AGENT"})
    void contextAndSourceRecheckRequireExceptionHandlingRole(String role) throws Exception {
        boolean permitted = !role.equals("COMPLIANCE") && !role.equals("AGENT");
        mvc.perform(get(PATH).with(user(principal(role)))).andExpect(status().is(permitted ? 200 : 403));
        mvc.perform(post(PATH + "/recheck").with(user(principal(role)))).andExpect(status().is(permitted ? 200 : 403));
        verify(service, times(permitted ? 1 : 0)).context(18L, 1L);
        verify(service, times(permitted ? 1 : 0)).recheckSchedule(18L, 1L, "reviewer");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "SETTLEMENT", "GA_ADMIN", "COMPLIANCE", "AGENT"})
    void financialWritesAndCandidatePrecheckRequireProcessRoleAndNoReasonInput(String role) throws Exception {
        boolean permitted = role.equals("SYSTEM_ADMIN") || role.equals("SETTLEMENT");
        mvc.perform(post(PATH).with(user(principal(role))).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is(permitted ? 200 : 403));
        mvc.perform(post(PATH + "/precheck").with(user(principal(role))).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is(permitted ? 200 : 403));
        verify(service, times(permitted ? 1 : 0)).correct(eq(18L), any(ExceptionPaymentCorrectionRequest.class), eq(1L), eq("reviewer"));
        verify(service, times(permitted ? 1 : 0)).preview(eq(18L), any(ExceptionPaymentCorrectionRequest.class), eq(1L));
    }

    private FgcUserDetails principal(String role) {
        var view = new AppUserView();
        view.setUserId(1L); view.setLoginId("reviewer"); view.setPasswordHash("{bcrypt}test");
        view.setUserName("reviewer"); view.setRoleCode(role);
        return new FgcUserDetails(view, true, true);
    }
}
