package com.susukkang.fgc.compare.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.security.AgentDataScopeService;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.compare.dto.CompareCategoryResponse;
import com.susukkang.fgc.compare.dto.CompareExplainResponse;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 설명 : 설계사에게 상품 비교 순위를 계산해 돌려주는 서비스(FUN-079).
 * 순위·금액은 서버가 결정론으로 확정하고(LLM 없이 완성), 요약 문장은 explain 으로 따로 받는다.
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

    /** 환수 노출액을 가정하는 해지 차월. 지금은 이 차월의 해약환급률을 환수율로 임시 사용한다(CompareRankingCalculator 참고). */
    @Value("${fgc.compare.clawback-month:12}")
    private int clawbackMonth;

    public List<CompareCategoryResponse> categories() {
        requireAgent();
        Map<String, Long> insurers = repository.findSellableOfferings(today()).stream()
                .collect(Collectors.groupingBy(Offering::groupCode,
                        Collectors.mapping(Offering::insurerId, Collectors.collectingAndThen(Collectors.toSet(), s -> (long) s.size()))));
        return CATEGORY_LABELS.entrySet().stream()
                .map(e -> new CompareCategoryResponse(e.getKey(), e.getValue(), insurers.getOrDefault(e.getKey(), 0L).intValue()))
                .toList();
    }

    public CompareRankingResponse rank(String categoryCode) {
        long agentId = requireAgent();
        String label = CATEGORY_LABELS.get(categoryCode);
        if (label == null) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_002, "categoryCode", Map.of("field", "categoryCode"), null);
        }
        LocalDate asOf = today();
        AgentProfile agent = repository.findAgent(agentId).orElseThrow(() -> new FgcBusinessException(FgcErrorCode.AUTH_005));
        CapSummary cap = capSummary(agentId);
        List<Offering> offerings = repository.findSellableOfferings(asOf).stream()
                .filter(o -> categoryCode.equals(o.groupCode())).toList();
        List<String> warnings = new ArrayList<>();
        if (offerings.isEmpty()) {
            warnings.add("NO_CANDIDATE");
            return empty(categoryCode, label, asOf, null, warnings, cap);
        }
        Long base = repository.averageMonthlyEquivalentPremium(agentId).orElse(null);
        if (base == null || base <= 0) {
            warnings.add("NO_BASE_PREMIUM");
            return empty(categoryCode, label, asOf, null, warnings, cap);
        }
        List<Candidate> candidates = offerings.stream().map(o -> candidate(o, agent, asOf))
                .filter(c -> !c.rules().isEmpty()).toList();
        if (candidates.isEmpty()) {
            warnings.add("NO_COMMISSION_RULE");
            return empty(categoryCode, label, asOf, base, warnings, cap);
        }
        if (candidates.stream().anyMatch(c -> c.clawbackRatePct() == null)) {
            warnings.add("REFUND_RATE_MISSING");
        }
        if (candidates.stream().anyMatch(c -> c.cap() == null)) {
            warnings.add("CAP_RULE_MISSING");
        }
        if (candidates.stream().map(Candidate::insurer).distinct().count() < MIN_INSURERS) {
            warnings.add("OTHER_INSURER_LT_3");
        }
        List<Result> ranked = CompareRankingCalculator.rank(candidates, base, asOf);
        List<CompareRankingResponse.Item> items = new ArrayList<>();
        for (int i = 0; i < ranked.size() && i < MAX_ITEMS; i++) {
            Result r = ranked.get(i);
            Candidate c = r.candidate();
            items.add(new CompareRankingResponse.Item(i + 1, c.offeringId(), c.insurer(), c.product(),
                    r.grossReceipt(), r.cappedReceipt(), r.carryover(), r.clawbackExposure(), r.conservativeNet(),
                    c.clawbackRatePct() == null ? null : c.clawbackRatePct().setScale(1, RoundingMode.HALF_UP).doubleValue(),
                    r.capImpactPct(), c.associationGrade(), r.evidence()));
        }
        String policyVersion = candidates.stream().flatMap(c -> c.rules().stream()).map(CompareRankingCalculator.Rule::policyVersion)
                .findFirst().orElse(null);
        CompareRankingResponse response = new CompareRankingResponse(null, categoryCode, label, asOf, policyVersion, base,
                clawbackMonth, warnings, cap, items);
        return withId(response, resultStore.save(agentId, response));
    }

    /** 순위 결과를 풀어 쓴 요약. 지금은 규칙형 문장만 만든다(LLM 요약은 LlmGateway 연동 단계에서 추가). */
    public CompareExplainResponse explain(String rankResultId) {
        long agentId = requireAgent();
        CompareRankingResponse ranking = resultStore.find(agentId, rankResultId)
                .orElseThrow(() -> new FgcBusinessException(FgcErrorCode.COMMON_004));
        return new CompareExplainResponse(rankResultId, ruleSummary(ranking.categoryLabel(), ranking.items()), "RULE", null);
    }

    /** 규칙형 요약: LLM 없이도 항상 만들 수 있고, 새 수치를 만들지 않고 계산된 값만 쓴다. */
    static String ruleSummary(String label, List<CompareRankingResponse.Item> items) {
        long insurers = items.stream().map(CompareRankingResponse.Item::insurer).distinct().count();
        var top = items.get(0);
        return "%s %d개 보험사의 상품 %d개를 보수적 순수령액 기준으로 정렬했어요. 1위는 %s %s(%,d원)예요. 선택은 설계사님이 하세요."
                .formatted(label, insurers, items.size(), top.insurer(), top.product(), top.conservativeNet());
    }

    private Candidate candidate(Offering o, AgentProfile agent, LocalDate asOf) {
        RefundRate refund = repository.findRefundRate(o, clawbackMonth, asOf).orElse(null);
        return new Candidate(o.offeringId(), o.insurer(), o.product(), repository.findCommissionRules(o, agent, asOf),
                refund == null ? null : refund.ratePct(), refund == null ? null : refund.policyVersion(),
                repository.findCapRule(o, asOf).orElse(null), repository.findAssociationGrade(o.offeringId(), asOf).orElse(null));
    }

    private CompareRankingResponse empty(String categoryCode, String label, LocalDate asOf, Long base, List<String> warnings,
                                         CapSummary cap) {
        return new CompareRankingResponse(null, categoryCode, label, asOf, null, base, clawbackMonth, warnings, cap, List.of());
    }

    private static CompareRankingResponse withId(CompareRankingResponse r, String id) {
        return new CompareRankingResponse(id, r.categoryCode(), r.categoryLabel(), r.asOf(), r.policyVersion(), r.basePremium(),
                r.clawbackMonth(), r.warnings(), r.capSummary(), r.items());
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
