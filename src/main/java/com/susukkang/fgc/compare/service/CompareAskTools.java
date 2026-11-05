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
        // 금액·비율·순위 번호는 서버 문장이 맡는다. 보험사·상품 이름 안의 숫자와 용어 '1,200% 룰'만 예외다.
        if (!AnswerNumberGuard.allowed(text, CompareSummaryService.names(ranking))) {
            return "NUMBER_GUARD";
        }
        return verifyComparisons(normalized);
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

    /** 글에서 보험사·상품 이름으로 언급된 순위 집합. 답에는 순위 번호를 쓸 수 없으므로 이름으로만 찾는다. */
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

    private static int firstMention(String part, Item item) {
        int best = -1;
        if (item != null) {
            for (String name : new String[] {item.product(), item.insurer()}) {
                int at = name == null || name.isBlank() ? -1 : part.indexOf(Normalizer.normalize(name, Normalizer.Form.NFKC));
                if (at >= 0 && (best < 0 || at < best)) {
                    best = at;
                }
            }
        }
        return best;
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
