package com.susukkang.fgc.compare.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.susukkang.fgc.ai.service.LlmGateway.ToolSpec;
import com.susukkang.fgc.compare.dto.CompareRankingResponse;
import com.susukkang.fgc.compare.dto.CompareRankingResponse.Item;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 설명 : 상품 비교 챗봇이 모델에 열어 주는 서버 도구와, 모델 답변을 검증하는 규칙(FUN-079, #889).
 * 도구는 서버가 계산한 순위 결과의 값만 돌려준다. 모델은 그 값으로 어느 쪽이 크고 작은지를 판단해 이유를 말로 쓰고,
 * 답에는 숫자(금액·비율·순위 번호·날짜)를 쓰지 않는다(인터페이스정의서 §4-4-6). 금액·순위는 서버 문장이 맡는다.
 * 검증은 숫자·한글 금액·추천 단정을 거절하고, 두 상품의 많고 적음을 말한 문장은 compare 결과의 방향과 맞는지 본다.
 * 요청 하나에 한 번 만들고 버린다(부른 compare 결과를 기억한다).
 */
final class CompareAskTools {

    static final String GET_ITEM = "get_item";
    static final String COMPARE = "compare";
    static final String EXPLAIN_TERM = "explain_term";

    /** 비교 문장이 가리킬 수 있는 항목. EXPECTED·CAP_IMPACT 는 compare 가 차이를 주지 않으므로 많고 적음을 말하면 근거 없는 주장으로 거절된다. */
    private enum Metric { NET, CAPPED, EXPECTED, CLAWBACK, CARRY, CAP_IMPACT }

    /** 답변에서 항목을 가리키는 말. 문장 안에서 가장 뒤에 있는(끝이 같으면 더 긴) 말이 그 문장의 항목이다. */
    private record MetricPhrase(String text, Set<Metric> metrics) {
    }

    private static final Set<Metric> RECEIPT = EnumSet.of(Metric.CAPPED, Metric.EXPECTED);
    private static final List<MetricPhrase> METRIC_PHRASES = List.of(
            new MetricPhrase("보수적 순수령액", EnumSet.of(Metric.NET)),
            new MetricPhrase("순수령액", EnumSet.of(Metric.NET)),
            new MetricPhrase("순수령", EnumSet.of(Metric.NET)),
            new MetricPhrase("한도 반영 수령 예상액", EnumSet.of(Metric.CAPPED)),
            new MetricPhrase("한도를 반영한 수령 예상액", EnumSet.of(Metric.CAPPED)),
            new MetricPhrase("한도 반영 수령", EnumSet.of(Metric.CAPPED)),
            new MetricPhrase("한도를 반영한 수령", EnumSet.of(Metric.CAPPED)),
            new MetricPhrase("한도 적용 전 수령 예상액", EnumSet.of(Metric.EXPECTED)),
            new MetricPhrase("한도 적용 전", EnumSet.of(Metric.EXPECTED)),
            new MetricPhrase("수령 예상액", RECEIPT),
            new MetricPhrase("수령 예상", RECEIPT),
            new MetricPhrase("수령액", RECEIPT),
            new MetricPhrase("환수 노출액", EnumSet.of(Metric.CLAWBACK)),
            new MetricPhrase("환수 노출", EnumSet.of(Metric.CLAWBACK)),
            new MetricPhrase("환수액", EnumSet.of(Metric.CLAWBACK)),
            new MetricPhrase("환수 부담", EnumSet.of(Metric.CLAWBACK)),
            new MetricPhrase("환수", EnumSet.of(Metric.CLAWBACK)),
            new MetricPhrase("이월 예정액", EnumSet.of(Metric.CARRY)),
            new MetricPhrase("이월 예정", EnumSet.of(Metric.CARRY)),
            new MetricPhrase("이월액", EnumSet.of(Metric.CARRY)),
            new MetricPhrase("이월", EnumSet.of(Metric.CARRY)),
            new MetricPhrase("한도 영향", EnumSet.of(Metric.CAP_IMPACT)),
            new MetricPhrase("한도 사용률", EnumSet.of(Metric.CAP_IMPACT)),
            new MetricPhrase("한도 소진", EnumSet.of(Metric.CAP_IMPACT)));

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NET_LABEL = "보수적 순수령액";
    private static final String CAPPED_LABEL = "한도 반영 수령 예상액";
    private static final String EXPECTED_LABEL = "한도 적용 전 수령 예상액";
    private static final String CLAWBACK_LABEL = "환수 노출액";
    private static final String CARRY_LABEL = "이월 예정액";
    private static final String CAP_IMPACT_LABEL = "한도 영향";
    private static final String ERROR = "error";
    private static final String UNIT_PERCENT = "%";
    /** '1위'처럼 순위를 말하는 표기. 소수점·쉼표 뒤의 숫자(금액 일부)는 순위로 보지 않는다. */
    private static final Pattern RANK_LABEL = Pattern.compile("(?<![\\d,.])(\\d{1,3})\\s*위(?!\\d)");
    /** '가장 큰/높은/많은'(최댓값) 또는 '가장 작은/낮은/적은'(최솟값) 주장. */
    private static final Pattern SUPERLATIVE = Pattern.compile("가장\\s*(큰|크|커|높|많|작은|작|낮|적)");
    /** '한도에 걸렸다·도달했다·초과했다' 처럼 한도에 닿았다는 주장. 조사가 없는 '한도 초과 시'(설명)는 잡지 않는다. */
    private static final Pattern CAP_HIT = Pattern.compile("한도(?:에|를|까지)\\s*(?:걸|도달|초과|넘|닿)");
    private static final Pattern CONDITIONAL = Pattern.compile("하면|이면|으면|라면|경우|만약|시\\s|시에");
    private static final Pattern NEGATION = Pattern.compile("않|없|못|아니");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+|\\n+");
    /** '커요'는 '크다'의 활용이라 '크'로는 잡히지 않는다. */
    private static final List<String> BIGGER_WORDS = List.of("많", "높", "크", "커");
    private static final List<String> SMALLER_WORDS = List.of("적", "낮", "작");
    /** 확정 추천·유리함 단정은 참고 순위 원칙에 어긋난다(FUN-079). 거절 안내에 쓰는 '추천' 자체는 막지 않는다. */
    // 정규식으로 넓히면 '추천드리지 않아요' 같은 거절 안내까지 막으므로, 같은 뜻의 변형을 문구로 하나씩 더한다.
    private static final List<String> RECOMMEND_PHRASES = List.of("추천합니다", "추천드립니다", "추천드려요", "추천해요", "추천해 드려요",
            "추천해드려요", "추천할게요", "추천해 드릴게요", "추천해드릴게요", "권합니다", "권해요", "권해 드려요", "권해드려요",
            "이득이 되", "이득이에요", "유리한 선택", "유리해요", "가장 좋", "팔면 좋", "선택하세요", "선택하시는 게",
            "더 낫습니다", "더 나아요", "좋은 선택이에요", "좋은 선택입니다");

    public static class RankArgs {
        public int rank;
    }

    public static class PairArgs {
        public int rankA;
        public int rankB;
    }

    public static class TermArgs {
        public String name;
    }

    /** compare 가 돌려준 차이 하나. biggerRank 는 값이 큰 쪽 순위(같으면 0). */
    private record Diff(Set<Integer> ranks, Metric metric, int biggerRank) {
    }

    private final CompareRankingResponse ranking;
    private final List<Diff> diffs = new ArrayList<>();

    CompareAskTools(CompareRankingResponse ranking) {
        this.ranking = ranking;
    }

    static List<ToolSpec> specs() {
        return List.of(
                new ToolSpec(GET_ITEM, "순위 하나의 금액(순수령액·수령 예상액·환수 노출액·이월 예정액)과 계산 근거를 돌려준다.", RankArgs.class),
                new ToolSpec(COMPARE, "두 순위의 금액과 차이, 어느 쪽이 더 큰지를 돌려준다.", PairArgs.class),
                new ToolSpec(EXPLAIN_TERM, "용어(1,200% 룰, 이월, 환수, 보수적 순수령액, 협회 수수료 등급)의 설명을 돌려준다.", TermArgs.class));
    }

    /** 모델이 부른 도구를 실행한다. 인자가 잘못되면 오류 JSON 을 돌려줘 모델이 스스로 고치게 한다. */
    String run(String tool, String argumentsJson) {
        try {
            return switch (tool) {
                case GET_ITEM -> getItem(JSON.readValue(argumentsJson, RankArgs.class).rank);
                case COMPARE -> {
                    PairArgs args = JSON.readValue(argumentsJson, PairArgs.class);
                    yield compare(args.rankA, args.rankB);
                }
                case EXPLAIN_TERM -> explainTerm(JSON.readValue(argumentsJson, TermArgs.class).name);
                default -> error("알 수 없는 도구입니다");
            };
        } catch (JsonProcessingException e) {
            return error("인자 형식이 맞지 않습니다");
        }
    }

    private String getItem(int rank) {
        Item item = find(rank);
        if (item == null) {
            return error("없는 순위입니다. 1위부터 " + ranking.items().size() + "위까지 있습니다");
        }
        ObjectNode node = describeItem(item);
        ArrayNode evidence = node.putArray("계산 근거");
        for (CompareRankingResponse.Evidence e : item.evidence()) {
            ObjectNode row = evidence.addObject();
            row.put("항목", e.label());
            row.put("규칙", e.rule());
            if (e.value() != null) {
                row.put("금액", won(e.value()));
            }
        }
        return node.toString();
    }

    private String compare(int rankA, int rankB) {
        Item a = find(rankA);
        Item b = find(rankB);
        if (a == null || b == null || rankA == rankB) {
            return error("서로 다른 두 순위가 필요합니다. 1위부터 " + ranking.items().size() + "위까지 있습니다");
        }
        ObjectNode node = JSON.createObjectNode();
        node.set("첫째", describeItem(a));
        node.set("둘째", describeItem(b));
        ObjectNode diff = node.putObject("차이");
        putDiff(diff, NET_LABEL, Metric.NET, a, b, a.conservativeNet(), b.conservativeNet());
        putDiff(diff, CAPPED_LABEL, Metric.CAPPED, a, b, a.cappedReceipt(), b.cappedReceipt());
        putDiff(diff, CLAWBACK_LABEL, Metric.CLAWBACK, a, b, a.clawbackExposure(), b.clawbackExposure());
        putDiff(diff, CARRY_LABEL, Metric.CARRY, a, b, a.carryover(), b.carryover());
        return node.toString();
    }

    private void putDiff(ObjectNode diff, String label, Metric metric, Item a, Item b, long av, long bv) {
        ObjectNode row = diff.putObject(label);
        int bigger = av == bv ? 0 : av > bv ? a.rank() : b.rank();
        diffs.add(new Diff(Set.of(a.rank(), b.rank()), metric, bigger));
        row.put("금액", won(Math.abs(av - bv)));
        row.put("값이 더 큰 쪽", bigger == 0 ? "같음" : bigger + "위");
    }

    private String explainTerm(String name) {
        if (name == null) {
            return error("용어 이름이 필요합니다");
        }
        return CompareAskService.GLOSSARY.stream()
                .filter(t -> t.name().contains(name) || name.contains(t.name()) || t.keywords().stream().anyMatch(name::contains))
                .findFirst()
                .map(t -> JSON.createObjectNode().put("용어", t.name()).put("설명", t.body()).toString())
                .orElseGet(() -> error("등록된 용어가 아닙니다"));
    }

    private ObjectNode describeItem(Item item) {
        ObjectNode node = JSON.createObjectNode();
        node.put("순위", item.rank() + "위");
        node.put("상품", item.insurer() + " " + item.product());
        node.put(NET_LABEL, won(item.conservativeNet()));
        node.put(CAPPED_LABEL, won(item.cappedReceipt()));
        node.put(EXPECTED_LABEL, won(item.expectedReceipt()));
        node.put(CLAWBACK_LABEL, won(item.clawbackExposure()));
        node.put(CARRY_LABEL, won(item.carryover()));
        if (item.capImpactPct() != null) {
            node.put(CAP_IMPACT_LABEL, percent(item.capImpactPct()));
        }
        return node;
    }

    /**
     * 한도 영향은 화면과 같게 소수 첫째 자리에서 절사한다(인터페이스정의서 capImpactPct, 화면정의서 공통규칙 3).
     * 99.96% 를 100.0% 로 올리면 화면(99.9%)과 다른 값을 모델이 근거로 삼고 한도를 다 쓴 것처럼 읽힌다.
     */
    static String percent(double pct) {
        return BigDecimal.valueOf(pct).setScale(1, RoundingMode.DOWN).toPlainString() + UNIT_PERCENT;
    }

    private Item find(int rank) {
        return ranking.items().stream().filter(i -> i.rank() == rank).findFirst().orElse(null);
    }

    /** 기본 Locale 에 따라 천 단위 구분자가 바뀌지 않게 Locale.ROOT 로 고정한다. */
    static String won(long amount) {
        return String.format(Locale.ROOT, "%,d원", amount);
    }

    private static String error(String message) {
        return JSON.createObjectNode().put(ERROR, message).toString();
    }

    // ---- 검증 ----

    /** 숫자·한글 금액, 확정 추천, 근거 없는·반대 방향의 비교 문장이면 사유 코드를, 통과하면 null 을 돌려준다. */
    String verify(String text) {
        if (text.isBlank()) {
            return "BLANK";
        }
        if (text.length() > CompareAskService.MAX_LENGTH) {
            return "TOO_LONG";
        }
        if (CompareSummaryService.FORBIDDEN.stream().anyMatch(text::contains)) {
            return "FORBIDDEN_PHRASE";
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        if (RECOMMEND_PHRASES.stream().anyMatch(normalized::contains)) {
            return "RECOMMENDATION";
        }
        // 금액·비율은 서버 문장이 맡는다. 보험사·상품 이름 안의 숫자, 용어 '1,200% 룰', 표시 중인 범위의 순위 표기('1위')만 예외다.
        if (!AnswerNumberGuard.allowed(withoutRankLabels(normalized), CompareSummaryService.names(ranking))) {
            return "NUMBER_GUARD";
        }
        String comparison = verifyComparisons(normalized);
        return comparison != null ? comparison : verifyClaims(normalized);
    }

    /**
     * 금액·비율 같은 숫자가 든 문장만 빼고 남은 문장을 돌려준다(#910). 모델이 지침을 어기고 금액을 한 문장에 써도 답 전체를 버리지 않는다.
     * 금액은 서버 문장이 보여 주므로 뺀 문장은 사용자에게 나가지 않는다. 남은 문장은 {@link #verify}로 다시 검증해야 한다.
     * 문장이 하나도 없거나 모두 빠졌으면 빈 문자열이다.
     */
    String keepNumberFreeSentences(String text) {
        List<String> kept = new ArrayList<>();
        int start = 0;
        Matcher ends = SENTENCE_END.matcher(text);
        while (start < text.length()) {
            int end = ends.find() ? ends.start() : text.length();
            String sentence = text.substring(start, end);
            String normalized = Normalizer.normalize(sentence, Normalizer.Form.NFKC);
            if (!sentence.isBlank()
                    && AnswerNumberGuard.allowed(withoutRankLabels(normalized), CompareSummaryService.names(ranking))) {
                kept.add(sentence.strip());
            }
            start = end == text.length() ? end : ends.end();
        }
        return String.join(" ", kept);
    }

    /** 표시 중인 순위(1위~) 범위 안의 'N위' 표기를 지운다. 범위 밖 순위(9위 등)는 남겨 숫자 검증이 막는다. 이미 NFKC 로 정규화한 글을 받는다. */
    private String withoutRankLabels(String normalized) {
        Matcher m = RANK_LABEL.matcher(normalized);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, find(Integer.parseInt(m.group(1))) != null ? " " : Matcher.quoteReplacement(m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * 두 상품의 많고 적음을 말한 문장('편한건강이 튼튼건강보다 환수 부담이 더 작아요')은 같은 두 상품·항목의 compare 결과와 방향이 맞아야 한다.
     * compare 를 부르지 않았으면 도구가 근거를 주지 않은 주장이므로 거절한다. 상품 하나만 말하거나 항목 이름이 없으면 보지 않는다.
     */
    private String verifyComparisons(String normalized) {
        int start = 0;
        Matcher ends = SENTENCE_END.matcher(normalized);
        while (start < normalized.length()) {
            int end = ends.find() ? ends.start() : normalized.length();
            String reason = verifyComparisonSentence(normalized.substring(start, end));
            if (reason != null) {
                return reason;
            }
            start = end == normalized.length() ? end : ends.end();
        }
        return null;
    }

    /**
     * 상품 하나를 두고 '가장 크다/작다', '한도에 걸렸다'고 쓴 문장은 서버 값과 맞아야 한다(#910).
     * 숫자 없이도 틀린 말을 할 수 있어서(최댓값을 다른 상품으로 말함, 이월이 없는데 한도에 걸렸다고 말함) 순위 결과의 값으로 직접 확인한다.
     * 상품이나 항목을 알아볼 수 없거나 가정·조건 설명이면 판단하지 않는다.
     */
    private String verifyClaims(String normalized) {
        int start = 0;
        Matcher ends = SENTENCE_END.matcher(normalized);
        while (start < normalized.length()) {
            int end = ends.find() ? ends.start() : normalized.length();
            String sentence = normalized.substring(start, end);
            String reason = verifySuperlative(sentence);
            if (reason == null) {
                reason = verifyCapState(sentence);
            }
            if (reason != null) {
                return reason;
            }
            start = end == normalized.length() ? end : ends.end();
        }
        return null;
    }

    private String verifySuperlative(String sentence) {
        Matcher superlative = SUPERLATIVE.matcher(sentence);
        if (!superlative.find()) {
            return null;
        }
        Set<Metric> metrics = metricsIn(sentence.substring(0, superlative.start()));
        Set<Integer> mentioned = ranksMentioned(sentence);
        if (metrics.isEmpty() || mentioned.size() != 1) {
            return null;
        }
        boolean biggest = "큰".equals(superlative.group(1)) || "크".equals(superlative.group(1)) || "커".equals(superlative.group(1))
                || "높".equals(superlative.group(1)) || "많".equals(superlative.group(1));
        return holdsExtreme(mentioned.iterator().next(), metrics, biggest) ? null : "SUPERLATIVE_MISMATCH";
    }

    /** 이 순위의 값이 항목(여럿이면 어느 하나)에서 최댓값(또는 최솟값)과 같은지. 값이 같은 상품은 모두 최대·최소다. */
    private boolean holdsExtreme(int rank, Set<Metric> metrics, boolean biggest) {
        Item self = find(rank);
        for (Metric metric : metrics) {
            Double mine = valueOf(self, metric);
            if (mine == null) {
                return true;
            }
            double extreme = ranking.items().stream().map(item -> valueOf(item, metric)).filter(v -> v != null)
                    .mapToDouble(Double::doubleValue).reduce(biggest ? Math::max : Math::min).orElse(mine);
            if (mine == extreme) {
                return true;
            }
        }
        return false;
    }

    private static Double valueOf(Item item, Metric metric) {
        return switch (metric) {
            case NET -> (double) item.conservativeNet();
            case CAPPED -> (double) item.cappedReceipt();
            case EXPECTED -> (double) item.expectedReceipt();
            case CLAWBACK -> (double) item.clawbackExposure();
            case CARRY -> (double) item.carryover();
            case CAP_IMPACT -> item.capImpactPct() == null ? null : item.capImpactPct().doubleValue();
        };
    }

    /** 한도에 걸렸다는 말은 말한 상품(없으면 모든 상품)에 이월 예정액이 있을 때만, 걸리지 않았다는 말은 이월이 없을 때만 맞다. */
    private String verifyCapState(String sentence) {
        Matcher hit = CAP_HIT.matcher(sentence);
        if (!hit.find() || CONDITIONAL.matcher(sentence).find()) {
            return null;
        }
        Set<Integer> mentioned = ranksMentioned(sentence);
        List<Item> scope = ranking.items().stream()
                .filter(item -> mentioned.isEmpty() || mentioned.contains(item.rank())).toList();
        boolean anyCarry = scope.stream().anyMatch(item -> item.carryover() > 0);
        boolean negated = NEGATION.matcher(sentence.substring(hit.end())).find();
        return negated == anyCarry ? "CAP_STATE_MISMATCH" : null;
    }

    private String verifyComparisonSentence(String sentence) {
        int pivot = sentence.indexOf("보다");
        if (pivot <= 0) {
            return null;
        }
        // '보수적 순수령액'의 '적'이 '적어요'로 읽히지 않게 항목 이름은 지우고 서술어만 본다.
        String predicate = sentence.substring(pivot).replace("보수적", " ");
        boolean saysBigger = BIGGER_WORDS.stream().anyMatch(predicate::contains);
        boolean saysSmaller = SMALLER_WORDS.stream().anyMatch(predicate::contains);
        Set<Integer> mentioned = ranksMentioned(sentence);
        Set<Metric> metrics = metricsIn(sentence);
        if (saysBigger == saysSmaller || mentioned.size() != 2 || metrics.isEmpty()) {
            return null;
        }
        boolean paired = false;
        for (Diff diff : diffs) {
            if (diff.ranks().equals(mentioned) && metrics.contains(diff.metric())) {
                paired = true;
                // 두 값이 같은데(biggerRank=0) 많다/적다고 말하면 틀린 방향이다.
                int subject = subjectOf(sentence, pivot, diff.ranks());
                if (diff.biggerRank() != 0 && subject != 0 && saysBigger == (subject == diff.biggerRank())) {
                    return null;
                }
            }
        }
        return paired ? "DIRECTION_MISMATCH" : "DIRECTION_NOT_FROM_TOOL";
    }

    /** 글에서 보험사·상품 이름(줄인 이름 포함)이나 순위 표기('3위')로 언급된 순위 집합. */
    Set<Integer> ranksMentioned(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        Set<Integer> ranks = new HashSet<>();
        for (Item item : ranking.items()) {
            if (firstMention(normalized, item) >= 0) {
                ranks.add(item.rank());
            }
        }
        return ranks;
    }

    /** 문장 안에서 가장 뒤에 쓴 항목 이름이 가리키는 항목들. 없으면 빈 집합. */
    private static Set<Metric> metricsIn(String sentence) {
        int bestEnd = -1;
        int bestLength = -1;
        Set<Metric> best = Set.of();
        for (MetricPhrase phrase : METRIC_PHRASES) {
            int found = sentence.lastIndexOf(phrase.text());
            if (found < 0) {
                continue;
            }
            int end = found + phrase.text().length();
            if (end > bestEnd || (end == bestEnd && phrase.text().length() > bestLength)) {
                bestEnd = end;
                bestLength = phrase.text().length();
                best = phrase.metrics();
            }
        }
        return best;
    }

    /**
     * 비교 문장의 주어(값이 크다/작다고 말하는 쪽). 'A가 B보다' 꼴은 보다 앞에 먼저 나온 상품, 'B보다 A가' 꼴은 보다 앞에 하나뿐이면 보다 뒤의 나머지 상품이다.
     * 알아볼 수 없으면 0.
     */
    private int subjectOf(String sentence, int pivot, Set<Integer> candidates) {
        String before = sentence.substring(0, pivot);
        String after = sentence.substring(pivot);
        int first = 0;
        int firstAt = Integer.MAX_VALUE;
        List<Integer> inFront = new ArrayList<>();
        for (int rank : candidates) {
            int at = firstMention(before, find(rank));
            if (at >= 0) {
                inFront.add(rank);
                if (at < firstAt) {
                    firstAt = at;
                    first = rank;
                }
            }
        }
        if (inFront.size() >= 2) {
            return first;
        }
        if (inFront.size() != 1) {
            return 0;
        }
        List<Integer> behind = candidates.stream()
                .filter(rank -> !inFront.contains(rank) && firstMention(after, find(rank)) >= 0)
                .toList();
        return behind.size() == 1 ? behind.get(0) : 0;
    }

    /**
     * 글에서 이 상품을 가리킨 첫 위치. 없으면 -1.
     * 상품 이름은 질문을 읽을 때와 같은 규칙({@link ProductNames})으로 찾는다. 그래서 질문을 따라 '든든 건강보험'처럼 줄여 쓴 답도 그 상품으로 본다.
     * 보험사 이름은 결과 안에 그 보험사 상품이 하나뿐일 때만 그 상품을 가리킨다고 본다(가보험이 상품을 둘 내면 '가보험'만으로는 어느 상품인지 모른다).
     */
    private int firstMention(String part, Item item) {
        if (item == null) {
            return -1;
        }
        int product = ProductNames.find(ranking.items(), part).stream()
                .filter(m -> m.rank() == item.rank()).mapToInt(ProductNames.Mention::at).findFirst().orElse(-1);
        boolean insurerUnique = ranking.items().stream().filter(o -> same(o.insurer(), item.insurer())).count() == 1;
        int insurer = insurerUnique ? indexOf(part, item.insurer()) : -1;
        int best = earliest(product, insurer);
        Matcher label = RANK_LABEL.matcher(part);
        while (label.find()) {
            if (Integer.parseInt(label.group(1)) == item.rank()) {
                return earliest(best, label.start());
            }
        }
        return best;
    }

    /** 두 위치 중 먼저 나온 것. 음수(없음)는 건너뛴다. 둘 다 없으면 -1. */
    private static int earliest(int a, int b) {
        if (a < 0) {
            return b;
        }
        return b >= 0 && b < a ? b : a;
    }

    private static int indexOf(String part, String name) {
        return name == null || name.isBlank() ? -1 : part.indexOf(Normalizer.normalize(name, Normalizer.Form.NFKC));
    }

    private static boolean same(String a, String b) {
        return a != null && a.equals(b);
    }

    /** 모델이 읽을 상품 목록(금액 없음). 금액은 도구로만 얻는다. */
    static String itemList(CompareRankingResponse ranking) {
        StringBuilder sb = new StringBuilder("[상품 목록]\n");
        for (Item item : ranking.items()) {
            sb.append("- %d위 %s %s%n".formatted(item.rank(), item.insurer(), item.product()));
        }
        return sb.toString();
    }
}
