package com.susukkang.fgc.policy.controller;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.PolicySourceClass;
import com.susukkang.fgc.common.code.PolicyStatus;
import com.susukkang.fgc.common.code.PolicyType;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import com.susukkang.fgc.policy.dto.CapRuleItemResponse;
import com.susukkang.fgc.policy.dto.CapRuleSetResponse;
import com.susukkang.fgc.policy.dto.CommissionRuleResponse;
import com.susukkang.fgc.policy.dto.PolicyDetailResponse;
import com.susukkang.fgc.policy.dto.PolicyVersionResponse;
import com.susukkang.fgc.policy.dto.RefundRateLineResponse;
import com.susukkang.fgc.policy.dto.RefundRateTableResponse;
import com.susukkang.fgc.policy.service.PolicyQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FGC-FUN-012·013: 정책·룰셋 조회 API(IF-API-09·10) — 역할 전체 조회 허용,
 * 잘못된 입력 400(FGC-COMMON-002), 없는 정책 버전 404(FGC-COMMON-004).
 */
@WebMvcTest(PolicyController.class)
@Import({PolicyController.class, GlobalExceptionHandler.class})
class PolicyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PolicyQueryService policyQueryService;

    @MockitoBean
    private FgcMessageResolver messageResolver;

    @MockitoBean
    private ConstraintErrorCodeResolver constraintErrorCodeResolver;

    private static PolicyVersionResponse samplePolicyVersion() {
        return new PolicyVersionResponse(
                1L, "REG-CAP-GA-2026-V1", "GA→설계사 1,200% 한도 규제",
                PolicyType.CAP_1200, PolicyType.CAP_1200.label(),
                PolicySourceClass.REGULATORY, PolicySourceClass.REGULATORY.label(),
                1, PolicyStatus.ACTIVE, PolicyStatus.ACTIVE.label(),
                LocalDate.of(2026, 7, 1), null,
                List.of("REG-08", "REG-09"),
                "admin01", "admin01", null);
    }

    @Test
    void returnsPolicyVersionsWithLabels() throws Exception {
        given(policyQueryService.findPolicyVersions(PolicyType.CAP_1200, LocalDate.of(2026, 7, 1), PolicyStatus.ACTIVE))
                .willReturn(List.of(samplePolicyVersion()));

        mockMvc.perform(get("/api/v1/policies")
                        .param("type", "CAP_1200")
                        .param("asOf", "2026-07-01")
                        .param("status", "ACTIVE")
                        .with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].policyVersionId").value(1))
                .andExpect(jsonPath("$.data[0].policyCode").value("REG-CAP-GA-2026-V1"))
                .andExpect(jsonPath("$.data[0].policyType").value("CAP_1200"))
                .andExpect(jsonPath("$.data[0].policyTypeLabel").value("1,200% 한도"))
                .andExpect(jsonPath("$.data[0].sourceClass").value("REGULATORY"))
                .andExpect(jsonPath("$.data[0].sourceClassLabel").value("규제"))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[0].statusLabel").value("적용중"))
                .andExpect(jsonPath("$.data[0].effectiveFrom").value("2026-07-01"))
                .andExpect(jsonPath("$.data[0].effectiveTo").isEmpty())
                .andExpect(jsonPath("$.data[0].regulationRefs[0]").value("REG-08"))
                .andExpect(jsonPath("$.error").isEmpty())
                .andExpect(jsonPath("$.requestId", matchesPattern("^\\d{8}-[0-9a-f]{6}$")));
    }

    /** FGC-FUN-012·013: 정책 조회는 "전체 조회" — 인증된 4개 역할 전부 허용된다(인터페이스정의서 §4-2). */
    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "GA_ADMIN", "SETTLEMENT", "COMPLIANCE"})
    void allowsEveryAuthenticatedRoleAndReturnsAnEmptyArray(String role) throws Exception {
        given(policyQueryService.findPolicyVersions(any(), any(), any())).willReturn(List.of());

        mockMvc.perform(get("/api/v1/policies")
                        .with(user("fgc-user").roles(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    /**
     * FGC-FUN-012·013: 상세 조회(IF-API-10)도 "전체 조회" — 인증된 4개 역할 전부 200.
     * 목록(IF-API-09)과 별개 메서드에 각각 @PreAuthorize 가 붙으므로 라우트별로 회귀 가드를 분리한다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "GA_ADMIN", "SETTLEMENT", "COMPLIANCE"})
    void allowsEveryAuthenticatedRoleOnPolicyDetail(String role) throws Exception {
        given(policyQueryService.findPolicyDetail(1L)).willReturn(new PolicyDetailResponse(
                samplePolicyVersion(), List.of(), List.of(), List.of(), List.of()));

        mockMvc.perform(get("/api/v1/policies/1")
                        .with(user("fgc-user").roles(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.header.policyVersionId").value(1))
                .andExpect(jsonPath("$.error").isEmpty());
    }

    @Test
    void returnsPolicyDetailWithNestedTabs() throws Exception {
        PolicyDetailResponse detail = new PolicyDetailResponse(
                samplePolicyVersion(),
                List.of("SRC-002"),
                List.of(new CommissionRuleResponse(
                        10L, PaymentStage.GA_TO_FC, PaymentStage.GA_TO_FC.label(),
                        null, null, "FC", "BASE_COMMISSION", "FC 기본수수료", "CURRENT",
                        1, 1, "RATE", "MONTHLY_PREMIUM", "650.000000", null, 100)),
                List.of(new CapRuleSetResponse(
                        20L, PaymentStage.INSURER_TO_GA, PaymentStage.INSURER_TO_GA.label(),
                        LocalDate.of(2026, 7, 1), null, 12, "12.0000", "3.0000",
                        "STANDARD_DEDUCTION_80", "90.0000",
                        List.of(new CapRuleItemResponse(
                                30L, "NEWCOMER_SUPPORT", "신인 정착지원금", "EXCLUDED",
                                "NEWCOMER_SUPPORT", true, "MANUAL_REVIEW", "REG-11 증빙 충족 시 제외")))),
                List.of(new RefundRateTableResponse(
                        40L, "테스트생명", "테스트종신보험", 240, "FC", null, true,
                        LocalDate.of(2026, 1, 1), null, "STD-LIFE-A", "FAQ v1",
                        List.of(new RefundRateLineResponse(12, "34.800000")))));
        given(policyQueryService.findPolicyDetail(1L)).willReturn(detail);

        mockMvc.perform(get("/api/v1/policies/1")
                        .with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.header.policyCode").value("REG-CAP-GA-2026-V1"))
                .andExpect(jsonPath("$.data.sourceRefs[0]").value("SRC-002"))
                .andExpect(jsonPath("$.data.commissionRules[0].paymentStageLabel").value("GA→설계사"))
                .andExpect(jsonPath("$.data.commissionRules[0].ratePct").value("650.000000"))
                .andExpect(jsonPath("$.data.commissionRules[0].fixedAmount").isEmpty())
                .andExpect(jsonPath("$.data.capRuleSets[0].premiumMultiplier").value("12.0000"))
                .andExpect(jsonPath("$.data.capRuleSets[0].warningUsagePct").value("90.0000"))
                .andExpect(jsonPath("$.data.capRuleSets[0].items[0].inclusionStatus").value("EXCLUDED"))
                .andExpect(jsonPath("$.data.refundRateTables[0].paymentTermMonths").value(240))
                .andExpect(jsonPath("$.data.refundRateTables[0].lines[0].refundRatePct").value("34.800000"))
                .andExpect(jsonPath("$.error").isEmpty());
    }

    @Test
    void rejectsAnInvalidAsOfFormat() throws Exception {
        mockMvc.perform(get("/api/v1/policies")
                        .param("asOf", "2026/07/01")
                        .with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"))
                .andExpect(jsonPath("$.error.field").value("asOf"));
    }

    @Test
    void rejectsAnUnknownPolicyType() throws Exception {
        mockMvc.perform(get("/api/v1/policies")
                        .param("type", "NOT_A_TYPE")
                        .with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"))
                .andExpect(jsonPath("$.error.field").value("type"));
    }

    @Test
    void rejectsANonNumericPolicyVersionId() throws Exception {
        mockMvc.perform(get("/api/v1/policies/abc")
                        .with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"))
                .andExpect(jsonPath("$.error.field").value("policyVersionId"));
    }

    @Test
    void returnsNotFoundForAMissingPolicyVersion() throws Exception {
        given(policyQueryService.findPolicyDetail(999L))
                .willThrow(new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("id", 999L)));

        mockMvc.perform(get("/api/v1/policies/999")
                        .with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-004"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/policies"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/policies/1"))
                .andExpect(status().isUnauthorized());
    }
}
