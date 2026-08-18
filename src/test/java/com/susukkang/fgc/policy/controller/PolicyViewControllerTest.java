package com.susukkang.fgc.policy.controller;

import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.code.PolicySourceClass;
import com.susukkang.fgc.common.code.PolicyStatus;
import com.susukkang.fgc.common.code.PolicyType;
import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import com.susukkang.fgc.common.web.ShellAdvice;
import com.susukkang.fgc.policy.dto.PolicyVersionResponse;
import com.susukkang.fgc.policy.service.PolicyQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * FGC-FUN-012·013: POL-W01 정책·룰셋 조회 화면 테스트.
 * 목록은 MPA 서버 렌더(IF-API-09 데이터), 탭 상세는 화면 스크립트의 IF-API-10 Ajax —
 * 여기서는 서버 렌더 부분(기준일 기본값·행 데이터·배지·근거 미기재·조회 전용)을 검증한다.
 */
@WebMvcTest(PolicyViewController.class)
@Import({PolicyViewController.class, SecurityConfig.class, ShellAdvice.class, GlobalExceptionHandler.class,
        FgcMessageResolver.class, ConstraintErrorCodeResolver.class, MessageSourceAutoConfiguration.class})
@TestPropertySource(properties = "fgc.demo-month=2026-07")
class PolicyViewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PolicyQueryService policyQueryService;

    /** FGC-FUN-012·013: 정책 조회는 "전체 조회" — 인증된 4개 역할 전부 200 (화면정의서 POL-W01 권한). */
    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "GA_ADMIN", "SETTLEMENT", "COMPLIANCE"})
    void 로그인한_전체_역할은_정책_룰셋을_조회할_수_있다(String roleCode) throws Exception {
        given(policyQueryService.findPolicyVersions(null, LocalDate.of(2026, 7, 1), null))
                .willReturn(samplePolicyVersions());

        mockMvc.perform(get("/policies").with(user(principal(roleCode))))
                .andExpect(status().isOk())
                .andExpect(view().name("policy/list"))
                .andExpect(model().attribute("asOf", LocalDate.of(2026, 7, 1)))
                .andExpect(content().string(containsString("FGC-UI-POL-W01")))
                .andExpect(content().string(containsString("정책 버전")))
                .andExpect(content().string(containsString("수수료 규칙")))
                .andExpect(content().string(containsString("1,200% 룰셋")))
                .andExpect(content().string(containsString("예상 해약환급률표")))
                .andExpect(content().string(containsString("REG-CAP-GA-2026-V1")))
                .andExpect(content().string(containsString("fgc-badge--src-regulatory")))
                .andExpect(content().string(containsString("REG-08 · REG-09")))
                .andExpect(content().string(containsString("data-policy-version-id=\"1\"")))
                .andExpect(content().string(containsString("data-policy-code=\"REG-CAP-GA-2026-V1\"")))
                .andExpect(content().string(containsString("data-version-no=\"1\"")))
                // 요율 수정 UI 금지 — 화면정의서 POL-W01 "막아야 할 것" (1차 조회 전용)
                .andExpect(content().string(not(containsString(">수정</button>"))))
                .andExpect(content().string(not(containsString(">등록</button>"))))
                .andExpect(content().string(not(containsString(">저장</button>"))));
    }

    /** 화면정의서 :490 — 근거(REG) 비어 있는 정책 행은 회색 + "근거 미기재". */
    @Test
    void 근거가_없는_정책_행은_근거_미기재로_표시한다() throws Exception {
        given(policyQueryService.findPolicyVersions(null, LocalDate.of(2026, 7, 1), null))
                .willReturn(samplePolicyVersions());

        mockMvc.perform(get("/policies").with(user(principal("SETTLEMENT"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("근거 미기재")))
                .andExpect(content().string(containsString("fgc-badge--src-assumption")))
                .andExpect(content().string(containsString("policy-row-missing-reference")));
    }

    /** 화면정의서 :499 — 기준일을 조회 조건에 꼭 넣는다. ?asOf= 가 정산월 기본값을 덮어쓴다. */
    @Test
    void 기준일_파라미터가_정산월_기본값을_덮어쓴다() throws Exception {
        given(policyQueryService.findPolicyVersions(null, LocalDate.of(2027, 6, 1), null))
                .willReturn(List.of());

        mockMvc.perform(get("/policies")
                        .param("asOf", "2027-06-01")
                        .with(user(principal("SETTLEMENT"))))
                .andExpect(status().isOk())
                .andExpect(model().attribute("asOf", LocalDate.of(2027, 6, 1)));

        verify(policyQueryService).findPolicyVersions(null, LocalDate.of(2027, 6, 1), null);
    }

    /** REG-19: 적용 시작일 당일은 포함하고 전날은 제외해 표시한다. */
    @Test
    void 적용_시작일_경계에서_정책_표시가_갈린다() throws Exception {
        LocalDate effectiveFrom = LocalDate.of(2026, 7, 1);
        given(policyQueryService.findPolicyVersions(null, effectiveFrom, null))
                .willReturn(samplePolicyVersions());
        given(policyQueryService.findPolicyVersions(null, effectiveFrom.minusDays(1), null))
                .willReturn(List.of());

        mockMvc.perform(get("/policies").param("asOf", effectiveFrom.toString())
                        .with(user(principal("SETTLEMENT"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("REG-CAP-GA-2026-V1")))
                .andExpect(content().string(containsString(">계속</td>")));

        mockMvc.perform(get("/policies").param("asOf", effectiveFrom.minusDays(1).toString())
                        .with(user(principal("SETTLEMENT"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("기준일에 적용되는 정책 버전이 없습니다")));
    }

    /** REG-19: 적용 종료일 당일은 포함하고 다음 날은 제외해 표시한다. */
    @Test
    void 적용_종료일_경계에서_정책_표시가_갈린다() throws Exception {
        LocalDate effectiveTo = LocalDate.of(2026, 12, 31);
        given(policyQueryService.findPolicyVersions(null, effectiveTo, null))
                .willReturn(closedPolicyVersions(effectiveTo));
        given(policyQueryService.findPolicyVersions(null, effectiveTo.plusDays(1), null))
                .willReturn(List.of());

        mockMvc.perform(get("/policies").param("asOf", effectiveTo.toString())
                        .with(user(principal("SETTLEMENT"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("REG-CAP-GA-2026-V1")))
                .andExpect(content().string(containsString(">2026-12-31</td>")));

        mockMvc.perform(get("/policies").param("asOf", effectiveTo.plusDays(1).toString())
                        .with(user(principal("SETTLEMENT"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("기준일에 적용되는 정책 버전이 없습니다")));
    }

    @Test
    void 조회_결과가_없으면_빈_목록_안내를_표시한다() throws Exception {
        given(policyQueryService.findPolicyVersions(null, LocalDate.of(2026, 7, 1), null))
                .willReturn(List.of());

        mockMvc.perform(get("/policies").with(user(principal("SETTLEMENT"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("기준일에 적용되는 정책 버전이 없습니다")));
    }

    @Test
    void 로그인하지_않으면_로그인_화면으로_이동한다() throws Exception {
        mockMvc.perform(get("/policies"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    private static List<PolicyVersionResponse> samplePolicyVersions() {
        return List.of(
                new PolicyVersionResponse(
                        1L, "REG-CAP-GA-2026-V1", "GA→설계사 1,200% 한도 규제",
                        PolicyType.CAP_1200, PolicyType.CAP_1200.label(),
                        PolicySourceClass.REGULATORY, PolicySourceClass.REGULATORY.label(),
                        1, PolicyStatus.ACTIVE, PolicyStatus.ACTIVE.label(),
                        LocalDate.of(2026, 7, 1), null,
                        List.of("REG-08", "REG-09"), "admin01", "gaadmin", null),
                // 근거 미기재 케이스 — regulationRefs 빈 목록 + PROJECT_ASSUMPTION 주황 배지
                new PolicyVersionResponse(
                        2L, "ASM-RECON-ZERO-2026-V1", "대사 허용오차 0원(프로젝트 가정)",
                        PolicyType.RECONCILIATION_TOLERANCE, PolicyType.RECONCILIATION_TOLERANCE.label(),
                        PolicySourceClass.PROJECT_ASSUMPTION, PolicySourceClass.PROJECT_ASSUMPTION.label(),
                        1, PolicyStatus.ACTIVE, PolicyStatus.ACTIVE.label(),
                        LocalDate.of(2026, 1, 1), null,
                        List.of(), "admin01", "gaadmin", null));
    }

    private static List<PolicyVersionResponse> closedPolicyVersions(LocalDate effectiveTo) {
        PolicyVersionResponse sample = samplePolicyVersions().getFirst();
        return List.of(new PolicyVersionResponse(
                sample.policyVersionId(), sample.policyCode(), sample.policyName(),
                sample.policyType(), sample.policyTypeLabel(), sample.sourceClass(), sample.sourceClassLabel(),
                sample.versionNo(), sample.status(), sample.statusLabel(), sample.effectiveFrom(), effectiveTo,
                sample.regulationRefs(), sample.createdBy(), sample.approvedBy(), sample.approvedAt()));
    }

    private static FgcUserDetails principal(String roleCode) {
        AppUserView view = new AppUserView();
        view.setUserId(1L);
        view.setLoginId("policy-viewer");
        view.setPasswordHash("{noop}x");
        view.setUserName("정책 조회자");
        view.setRoleCode(roleCode);
        view.setAccountStatus("ACTIVE");
        return new FgcUserDetails(view, true, true);
    }
}
