package com.susukkang.fgc.compare.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.susukkang.fgc.common.security.AgentDataScopeService;
import com.susukkang.fgc.compare.dto.CompareRankingResponse;
import com.susukkang.fgc.compare.repository.CompareRankingRepository;
import com.susukkang.fgc.compare.repository.CompareRankingRepository.AgentProfile;
import com.susukkang.fgc.compare.repository.CompareRankingRepository.CapRow;
import com.susukkang.fgc.compare.repository.CompareRankingRepository.Offering;
import com.susukkang.fgc.compare.service.ClawbackRuleResolver.RuleRow;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

/** 규칙 버전 게이트(CT-A02)와 환수율 출처(D-01: 해약환급률이 아니라 환수 규칙)를 저장소 없이 확인한다. DB 쿼리는 통합 테스트가 본다. */
class CompareRankingServiceTest {

    private static final long AGENT = 7L;
    private static final long BASE_ITEM = 11L;
    private static final long MAINTENANCE_ITEM = 12L;
    private static final Offering OFFERING = new Offering(10L, 1L, "가보험", 100L, "든든건강", "HEALTH_PROTECTION");

    private final CompareRankingRepository repository = mock(CompareRankingRepository.class);
    private final AgentDataScopeService agentScope = mock(AgentDataScopeService.class);
    private final CompareRankingService service = new CompareRankingService(repository, agentScope, new CompareRankResultStore(),
            mock(CompareSummaryService.class), mock(CompareAskService.class));

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "ruleVersion", CompareRankingCalculator.RULE_VERSION);
        ReflectionTestUtils.setField(service, "clawbackMonth", 12);
        when(agentScope.currentAgentId()).thenReturn(AGENT);
        when(repository.findCapSummary(AGENT)).thenReturn(new CapRow(84.123456, "WARNING", 3));
        when(repository.findAgent(AGENT)).thenReturn(Optional.of(new AgentProfile(1L, "FC")));
        when(repository.findSellableOfferings(any())).thenReturn(List.of(OFFERING));
        when(repository.averageMonthlyEquivalentPremium(AGENT)).thenReturn(Optional.of(100_000L));
        // 기본수수료 1회차 650%, 유지수수료 2~12회차 5%
        when(repository.findCommissionRules(any(), any(), any())).thenReturn(List.of(
                new CommissionRuleResolver.RuleRow(1, 100, BASE_ITEM, 1, 1, new BigDecimal("650"), null, "기본수수료", "GA v1",
                        false, false, true, false, 100),
                new CommissionRuleResolver.RuleRow(2, 100, MAINTENANCE_ITEM, 2, 12, new BigDecimal("5"), null, "유지수수료", "GA v1",
                        false, false, true, false, 100)));
        when(repository.findCapRule(any(), any())).thenReturn(Optional.of(new CompareRankingCalculator.CapRule(
                new BigDecimal("12"), "CAP v1", 5L)));
        when(repository.findCapItemInclusion(anyLong())).thenReturn(Map.of(BASE_ITEM, "INCLUDED", MAINTENANCE_ITEM, "INCLUDED"));
        when(repository.findAssociationGrade(anyLong(), any())).thenReturn(Optional.empty());
    }

    private static RuleRow clawback(int from, int to, String pct) {
        return new RuleRow(200, "CLAW v1", BASE_ITEM, false, false, from, to, ClawbackRuleResolver.BASIS_CODE, new BigDecimal(pct), "{}");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"TEST-RULE", "compare-net-v0"})
    @DisplayName("설정이 구현 산식 버전과 다르면 아무 문자열이어도 순위를 만들지 않는다(CT-A02)")
    void ruleVersionMustMatchTheImplementedFormula(String configured) {
        ReflectionTestUtils.setField(service, "ruleVersion", configured);

        CompareRankingResponse response = service.rank("HEALTH_PROTECTION", null);

        assertThat(response.status()).isEqualTo(CompareRankingResponse.RULE_NOT_CONFIGURED);
        assertThat(response.items()).isEmpty();
        assertThat(response.rankResultId()).isNull();
        assertThat(response.ruleVersion()).isNull();
        verify(repository, never()).findSellableOfferings(any());
    }

    @Test
    @DisplayName("환수 노출액은 환수 규칙의 항목별 환수율로 계산하고, 규칙이 없는 항목은 환수 대상이 아니다")
    void clawbackComesFromTheClawbackRule() {
        when(repository.findClawbackRules(any(), any())).thenReturn(List.of(clawback(0, 11, "30")));

        CompareRankingResponse response = service.rank("HEALTH_PROTECTION", null);

        assertThat(response.status()).isEqualTo(CompareRankingResponse.READY);
        assertThat(response.ruleVersion()).isEqualTo(CompareRankingCalculator.RULE_VERSION);
        CompareRankingResponse.Item item = response.items().get(0);
        // 650,000 × 30% = 195,000. 유지수수료 55,000원은 환수 대상이 아니다
        assertThat(item.clawbackExposure()).isEqualTo(195_000);
        assertThat(item.conservativeNet()).isEqualTo(650_000 + 55_000 - 195_000);
        assertThat(item.evidence()).filteredOn(e -> e.label().equals("환수 노출액"))
                .extracting(e -> e.source() + ":" + e.policyVersion()).containsOnly("환수 규칙:CLAW v1");
        assertThat(response.capSummary().usagePct()).isEqualTo(84.123456);
    }

    @Test
    @DisplayName("해지 가정 12차월은 경과 11개월이라 0~11개월 구간을, 13차월은 다음 구간을 쓴다")
    void clawbackMonthIsConvertedToElapsedMonths() {
        when(repository.findClawbackRules(any(), any())).thenReturn(List.of(clawback(0, 11, "100"), clawback(12, 23, "50")));

        assertThat(service.rank("HEALTH_PROTECTION", null).items().get(0).clawbackExposure()).isEqualTo(650_000);

        ReflectionTestUtils.setField(service, "clawbackMonth", 13);
        assertThat(service.rank("HEALTH_PROTECTION", null).items().get(0).clawbackExposure()).isEqualTo(325_000);
    }

    @Test
    @DisplayName("환수 규칙이 없으면(해약환급률표가 있어도) 0원으로 계산하지 않고 사유와 함께 제외한다")
    void missingClawbackRuleExcludesTheOffering() {
        when(repository.findClawbackRules(any(), any())).thenReturn(List.of());

        CompareRankingResponse response = service.rank("HEALTH_PROTECTION", null);

        assertThat(response.status()).isEqualTo(CompareRankingResponse.INSUFFICIENT_DATA);
        assertThat(response.warnings()).containsExactly("ALL_EXCLUDED");
        assertThat(response.excludedItems()).singleElement()
                .satisfies(excluded -> assertThat(excluded.reasonCodes()).containsExactly("CLAWBACK_RATE_MISSING"));
    }
}
