package com.susukkang.fgc.compare.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.security.AgentDataScopeService;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.compare.dto.CompareCategoryResponse;
import com.susukkang.fgc.compare.dto.CompareExplainResponse;
import com.susukkang.fgc.compare.dto.CompareRankingFilters;
import com.susukkang.fgc.compare.dto.CompareRankingResponse;
import com.susukkang.fgc.compare.dto.CompareRankingResponse.CapSummary;
import com.susukkang.fgc.compare.repository.CompareRankingRepository;
import com.susukkang.fgc.compare.repository.CompareRankingRepository.AgentProfile;
import com.susukkang.fgc.compare.repository.CompareRankingRepository.Offering;
import com.susukkang.fgc.compare.repository.CompareRankingRepository.RefundRate;
import com.susukkang.fgc.compare.service.CompareRankingCalculator.Candidate;
import com.susukkang.fgc.compare.service.CompareRankingCalculator.Result;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 설명 : 설계사에게 상품 비교 순위를 계산해 돌려주는 서비스(FUN-079).
 * 순위·금액은 서버가 결정론으로 확정하고(LLM 없이 완성), 요약 문장은 explain 으로 따로 받는다.
 * 승인된 계산 기준(fgc.compare.rule-version)이 없으면 순위를 만들지 않는다.
 * 설계사 ID는 세션에서만 얻는다(FUN-085).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CompareRankingService {

    /** 화면 순서대로의 카테고리(상품군 코드 → 화면 이름). */
    static final Map<String, String> CATEGORY_LABELS = new LinkedHashMap<>();
    static {
        CATEGORY_LABELS.put("HEALTH_PROTECTION", "건강보험");
        CATEGORY_LABELS.put("TERM_PROTECTION", "정기보험");
        CATEGORY_LABELS.put("LONG_TERM_NONLIFE", "장기상해보험");
        CATEGORY_LABELS.put("SAVINGS", "연금저축보험");
    }

    /** 순위에 올리는 최대 상품 수(1~5위). */
    private static final int MAX_ITEMS = 5;
    /** 다른 보험사 상품이 이 수보다 적으면 경고한다(FGC-CMPR-001 취지). */
    private static final int MIN_INSURERS = 3;

    private final CompareRankingRepository repository;
    private final AgentDataScopeService agentScope;
    private final CompareRankResultStore resultStore;
    private final CompareSummaryService summaryService;

    /** 순위 계산 기준 버전(D-01 승인본). 비어 있으면 순위를 만들지 않고 RULE_NOT_CONFIGURED 로 응답한다. */
    @Value("${fgc.compare.rule-version:}")
    private String ruleVersion;

    /** 환수 노출액을 가정하는 해지 차월. 지금은 이 차월의 해약환급률을 환수율로 임시 사용한다(CompareRankingCalculator 참고). */
    @Value("${fgc.compare.clawback-month:12}")
    private int clawbackMonth;

    public List<CompareCategoryResponse> categories() {
        requireAgent();
        Map<String, List<Offering>> byGroup = repository.findSellableOfferings(today()).stream()
                .collect(Collectors.groupingBy(Offering::groupCode));
        return CATEGORY_LABELS.entrySet().stream().map(e -> {
            List<CompareCategoryResponse.InsurerOption> insurers = byGroup.getOrDefault(e.getKey(), List.of()).stream()
                    .map(o -> new CompareCategoryResponse.InsurerOption(o.insurerId(), o.insurer()))
                    .distinct().sorted(Comparator.comparing(CompareCategoryResponse.InsurerOption::name)).toList();
            return new CompareCategoryResponse(e.getKey(), e.getValue(), insurers.size(), insurers);
        }).toList();
    }

    public CompareRankingResponse rank(String categoryCode, CompareRankingFilters requested) {
        long agentId = requireAgent();
        CompareRankingFilters filters = (requested == null ? CompareRankingFilters.NONE : requested).normalized();
        String label = CATEGORY_LABELS.get(categoryCode);
        if (label == null) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_002, "categoryCode", Map.of("field", "categoryCode"), null);
        }
        Scope scope = new Scope(categoryCode, label, today(), capSummary(agentId), filters);
        // 순위 계산 기준(D-01)이 승인·설정되기 전에는 임의 점수·순위를 만들지 않는다(FUN-079, CT-A02).
        if (ruleVersion == null || ruleVersion.isBlank()) {
            return response(scope, new Outcome(CompareRankingResponse.RULE_NOT_CONFIGURED, null, null,
                    List.of("RULE_NOT_CONFIGURED"), List.of(), List.of()));
        }
        Outcome outcome = compute(agentId, scope);
        CompareRankingResponse response = response(scope, outcome);
        return outcome.items().isEmpty() ? response : withId(response, resultStore.save(agentId, response));
    }

    private Outcome compute(long agentId, Scope scope) {
        AgentProfile agent = repository.findAgent(agentId).orElseThrow(() -> new FgcBusinessException(FgcErrorCode.AUTH_005));
        CompareRankingFilters filters = scope.filters();
        List<Offering> offerings = repository.findSellableOfferings(scope.asOf()).stream()
                .filter(o -> scope.categoryCode().equals(o.groupCode()))
                .filter(o -> !filters.hasInsurers() || filters.insurerIds().contains(o.insurerId())).toList();
        if (offerings.isEmpty()) {
            return insufficient(null, filters.hasInsurers() ? "FILTER_NO_MATCH" : "NO_CANDIDATE", List.of());
        }
        Long base = repository.averageMonthlyEquivalentPremium(agentId).orElse(null);
        if (base == null || base <= 0) {
            return insufficient(null, "NO_BASE_PREMIUM", List.of());
        }
        List<Candidate> candidates = new ArrayList<>();
        List<CompareRankingResponse.Excluded> excluded = new ArrayList<>();
        for (Offering offering : offerings) {
            Built built = build(offering, agent, scope.asOf());
            if (built.candidate() != null) {
                candidates.add(built.candidate());
            } else {
                excluded.add(built.excluded());
            }
        }
        if (candidates.isEmpty()) {
            return insufficient(base, "ALL_EXCLUDED", excluded);
        }
        return ranked(candidates, excluded, base, scope);
    }

    private static Outcome ranked(List<Candidate> candidates, List<CompareRankingResponse.Excluded> excluded, long base,
                                  Scope scope) {
        // 경고·정책 버전은 한도 필터까지 거친 최종 표시 상품 기준으로 만든다.
        List<Result> results = CompareRankingCalculator.rank(candidates, base, scope.asOf()).stream()
                .filter(r -> !scope.filters().capOverflowExcluded() || r.carryover() == 0).toList();
        if (results.isEmpty()) {
            return insufficient(base, "FILTER_NO_MATCH", excluded);
        }
        List<String> warnings = new ArrayList<>();
        if (results.stream().map(r -> r.candidate().insurer()).distinct().count() < MIN_INSURERS) {
            warnings.add("OTHER_INSURER_LT_3");
        }
        if (!excluded.isEmpty()) {
            warnings.add("SOME_EXCLUDED");
        }
        List<CompareRankingResponse.Item> items = new ArrayList<>();
        for (int i = 0; i < results.size() && i < MAX_ITEMS; i++) {
            items.add(item(i + 1, results.get(i)));
        }
        // 표시 상품의 정책 버전이 하나로 같을 때만 대표 버전으로 쓴다. 섞여 있으면 상품별 evidence 의 버전을 본다.
        List<String> versions = results.stream().limit(MAX_ITEMS).flatMap(r -> r.candidate().rules().stream())
                .map(CompareRankingCalculator.Rule::policyVersion).distinct().toList();
        String policyVersion = versions.size() == 1 ? versions.get(0) : null;
        return new Outcome(CompareRankingResponse.READY, policyVersion, base, warnings, items, excluded);
    }

    private static CompareRankingResponse.Item item(int rank, Result r) {
        Candidate c = r.candidate();
        Double refundPct = c.clawbackRatePct() == null ? null : c.clawbackRatePct().setScale(1, RoundingMode.HALF_UP).doubleValue();
        return new CompareRankingResponse.Item(rank, c.offeringId(), c.insurer(), c.product(), r.grossReceipt(), r.cappedReceipt(),
                r.carryover(), r.clawbackExposure(), r.conservativeNet(), refundPct, r.capImpactPct(), c.associationGrade(),
                r.evidence());
    }

    /** 순위 결과를 풀어 쓴 요약. LLM 요약이 되면 그것을, 안 되면(키 없음·장애·검증 실패) 규칙형 문장을 돌려준다. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CompareExplainResponse explain(String rankResultId) {
        long agentId = requireAgent();
        if (resultStore.ownedByOther(agentId, rankResultId)) {
            throw new FgcBusinessException(FgcErrorCode.AUTH_003);
        }
        CompareRankingResponse ranking = resultStore.find(agentId, rankResultId)
                .orElseThrow(() -> new FgcBusinessException(FgcErrorCode.COMMON_004));
        String llm = resultStore.findSummary(agentId, rankResultId)
                .or(() -> summaryService.summarize(ranking).map(text -> {
                    resultStore.saveSummary(agentId, rankResultId, text);
                    return text;
                })).orElse(null);
        if (llm != null) {
            return new CompareExplainResponse(rankResultId, llm, "LLM", null);
        }
        return new CompareExplainResponse(rankResultId, ruleSummary(ranking.categoryLabel(), ranking.items()), "RULE", null);
    }

    /** 규칙형 요약: LLM 없이도 항상 만들 수 있고, 새 수치를 만들지 않고 계산된 값만 쓴다. */
    static String ruleSummary(String label, List<CompareRankingResponse.Item> items) {
        long insurers = items.stream().map(CompareRankingResponse.Item::insurer).distinct().count();
        var top = items.get(0);
        return "%s %d개 보험사의 상품 %d개를 보수적 순수령액 기준으로 정렬했어요. 1위는 %s %s(%,d원)예요. 선택은 설계사님이 하세요."
                .formatted(label, insurers, items.size(), top.insurer(), top.product(), top.conservativeNet());
    }

    /** 요청 한 건의 공통 맥락: 카테고리, 기준일, 본인 한도 현황. */
    private record Scope(String categoryCode, String label, LocalDate asOf, CapSummary cap, CompareRankingFilters filters) {
    }

    /** 순위 계산 결과. items 가 비어 있으면 status 가 이유를 말한다. */
    private record Outcome(String status, String policyVersion, Long base, List<String> warnings,
                           List<CompareRankingResponse.Item> items, List<CompareRankingResponse.Excluded> excluded) {
    }

    private record Built(Candidate candidate, CompareRankingResponse.Excluded excluded) {
    }

    /** 상품 하나의 계산 입력을 만든다. 자료가 없으면 0이나 최하위로 바꾸지 않고 사유 코드와 함께 제외한다. */
    private Built build(Offering o, AgentProfile agent, LocalDate asOf) {
        CommissionRuleResolver.Resolution resolution = CommissionRuleResolver.resolve(
                repository.findCommissionRules(o, agent, asOf), CompareRankingCalculator.FIRST_YEAR_INSTALLMENTS);
        RefundRate refund = repository.findRefundRate(o, clawbackMonth, asOf).orElse(null);
        CompareRankingCalculator.CapRule cap = repository.findCapRule(o, asOf).orElse(null);
        if (resolution.excludeReason() != null || refund == null || cap == null) {
            return excluded(o, missingReasons(resolution, refund, cap));
        }
        Map<Long, String> inclusion = cap.capRuleSetId() == null ? Map.of() : repository.findCapItemInclusion(cap.capRuleSetId());
        // 한도 규칙에 구분이 없는 항목은 산입 여부를 추정하지 않고 확인 필요로 본다(REG-11).
        if (resolution.runs().stream().anyMatch(run -> "REVIEW_REQUIRED".equals(inclusionOf(inclusion, run)))) {
            return excluded(o, List.of("CAP_ITEM_REVIEW_REQUIRED"));
        }
        List<CompareRankingCalculator.Rule> rules = resolution.runs().stream()
                .map(run -> new CompareRankingCalculator.Rule(run.from(), run.to(), run.rule().ratePct(), run.rule().fixedAmount(),
                        run.rule().label(), run.rule().policyVersion(), "EXCLUDED".equals(inclusionOf(inclusion, run))))
                .toList();
        return new Built(new Candidate(o.offeringId(), o.insurer(), o.product(), rules, refund.ratePct(), refund.policyVersion(),
                cap, repository.findAssociationGrade(o.offeringId(), asOf).orElse(null)), null);
    }

    private static String inclusionOf(Map<Long, String> inclusion, CommissionRuleResolver.Run run) {
        return inclusion.getOrDefault(run.rule().itemId(), "REVIEW_REQUIRED");
    }

    private static List<String> missingReasons(CommissionRuleResolver.Resolution resolution, RefundRate refund,
                                               CompareRankingCalculator.CapRule cap) {
        List<String> reasons = new ArrayList<>();
        if (resolution.excludeReason() != null) {
            reasons.add(resolution.excludeReason());
        }
        if (refund == null) {
            reasons.add("CLAWBACK_RATE_MISSING");
        }
        if (cap == null) {
            reasons.add("CAP_RULE_MISSING");
        }
        return reasons;
    }

    private static Built excluded(Offering o, List<String> reasons) {
        return new Built(null, new CompareRankingResponse.Excluded(o.offeringId(), o.insurer(), o.product(), List.copyOf(reasons)));
    }

    private static Outcome insufficient(Long base, String warning, List<CompareRankingResponse.Excluded> excluded) {
        return new Outcome(CompareRankingResponse.INSUFFICIENT_DATA, null, base, List.of(warning), List.of(), excluded);
    }

    private CompareRankingResponse response(Scope scope, Outcome outcome) {
        boolean configured = !CompareRankingResponse.RULE_NOT_CONFIGURED.equals(outcome.status());
        return new CompareRankingResponse(null, outcome.status(), scope.categoryCode(), scope.label(), scope.asOf(),
                configured ? ruleVersion : null, outcome.policyVersion(), outcome.base(), clawbackMonth, outcome.warnings(),
                scope.cap(), outcome.items(), outcome.excluded(), scope.filters());
    }

    private static CompareRankingResponse withId(CompareRankingResponse r, String id) {
        return new CompareRankingResponse(id, r.status(), r.categoryCode(), r.categoryLabel(), r.asOf(), r.ruleVersion(),
                r.policyVersion(), r.basePremium(), r.clawbackMonth(), r.warnings(), r.capSummary(), r.items(), r.excludedItems(),
                r.filters());
    }

    private CapSummary capSummary(long agentId) {
        var row = repository.findCapSummary(agentId);
        Double pct = row.usagePct() == null ? null
                : BigDecimal.valueOf(row.usagePct()).setScale(1, RoundingMode.HALF_UP).doubleValue();
        return new CapSummary(pct, row.status(), row.contractCount());
    }

    /** 설계사가 아니면 접근을 거부한다. 설계사 ID는 요청이 아니라 인증 세션에서만 얻는다. */
    private long requireAgent() {
        Long agentId = agentScope.currentAgentId();
        if (agentId == null) {
            throw new FgcBusinessException(FgcErrorCode.AUTH_003);
        }
        return agentId;
    }

    private static LocalDate today() {
        return LocalDate.now(DateUtil.SEOUL_ZONE);
    }
}
