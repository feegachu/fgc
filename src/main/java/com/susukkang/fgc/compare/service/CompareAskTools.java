package com.susukkang.fgc.compare.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.susukkang.fgc.ai.service.LlmGateway.ToolSpec;
import com.susukkang.fgc.compare.dto.CompareRankingResponse;
import com.susukkang.fgc.compare.dto.CompareRankingResponse.Item;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 설명 : 상품 비교 챗봇이 모델에 열어 주는 서버 도구와, 모델 답변을 도구 결과로 검증하는 규칙(FUN-079, #889).
 * 도구는 서버가 계산한 순위 결과의 값만 돌려주고, 돌려준 금액마다 '어느 순위의 어느 항목인지'를 기억한다.
 * 검증은 답변의 모든 금액·비율이 도구가 준 값이고, 금액 바로 앞에 가장 가까이 쓴 상품(순위·이름)과 항목 이름이 그 값의 것인지,
 * 두 상품의 차이를 말할 때 많고 적음의 방향이 서버 값과 같은지를 본다. 요청 하나에 한 번 만들고 버린다(상태를 가진다).
 */
final class CompareAskTools {

    static final String GET_ITEM = "get_item";
    static final String COMPARE = "compare";
    static final String EXPLAIN_TERM = "explain_term";

    /** 금액이 가리킬 수 있는 항목. */
    private enum Metric { NET, CAPPED, EXPECTED, CLAWBACK, CARRY, CAP_IMPACT }

    /** 답변에서 항목을 가리키는 말. 금액 앞에 가장 가까이 있는(끝이 가장 뒤인, 같으면 더 긴) 말이 그 금액의 항목이다. */
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
    private static final String UNIT_WON = "원";
    private static final String UNIT_PERCENT = "%";
    private static final Pattern NUMBER = Pattern.compile("(\\d[\\d,]*+(?:\\.\\d++)?)\\s*+(원|%|순위|위|등(?!급))?", Pattern.CANON_EQ);
    private static final Pattern RANK_LABEL = Pattern.compile("(\\d{1,3})\\s*+위");
    private static final Pattern TERM_1200 = Pattern.compile("1,?200\\s*%");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+|\\n+");
    private static final List<String> BIGGER_WORDS = List.of("많", "높", "크");
    private static final List<String> SMALLER_WORDS = List.of("적", "낮", "작");
    /** 확정 추천·유리함 단정은 참고 순위 원칙에 어긋난다(FUN-079). 거절 안내에 쓰는 '추천' 자체는 막지 않는다. */
    private static final List<String> RECOMMEND_PHRASES = List.of("추천합니다", "추천드립니다", "추천해요", "추천해 드려요", "권합니다", "권해요",
            "이득이 되", "이득이에요", "유리한 선택", "유리해요", "가장 좋", "팔면 좋", "선택하세요", "선택하시는 게");

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

    /** 도구가 돌려준 값 하나가 가리키는 대상. ranks 는 값이 속한 순위(차이는 두 순위), biggerRank 는 차이에서 값이 큰 쪽(같거나 아니면 0). */
    private record Fact(List<Integer> ranks, Set<Metric> metrics, int biggerRank) {
    }

    private final CompareRankingResponse ranking;
    private final Map<String, List<Fact>> factsByValue = new HashMap<>();

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
                row.put("금액", remember(won(e.value()), List.of(item.rank()), metricsOf(e.label()), 0));
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
        row.put("금액", remember(won(Math.abs(av - bv)), List.of(a.rank(), b.rank()), EnumSet.of(metric), bigger));
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
        List<Integer> self = List.of(item.rank());
        node.put("순위", item.rank() + "위");
        node.put("상품", item.insurer() + " " + item.product());
        node.put(NET_LABEL, remember(won(item.conservativeNet()), self, EnumSet.of(Metric.NET), 0));
        node.put(CAPPED_LABEL, remember(won(item.cappedReceipt()), self, EnumSet.of(Metric.CAPPED), 0));
        node.put(EXPECTED_LABEL, remember(won(item.expectedReceipt()), self, EnumSet.of(Metric.EXPECTED), 0));
        node.put(CLAWBACK_LABEL, remember(won(item.clawbackExposure()), self, EnumSet.of(Metric.CLAWBACK), 0));
        node.put(CARRY_LABEL, remember(won(item.carryover()), self, EnumSet.of(Metric.CARRY), 0));
        if (item.capImpactPct() != null) {
            node.put(CAP_IMPACT_LABEL, remember("%.1f%%".formatted(item.capImpactPct()), self, EnumSet.of(Metric.CAP_IMPACT), 0));
        }
        return node;
    }

    /** 도구가 돌려주는 값을 기억하고 그대로 돌려준다. */
    private String remember(String value, List<Integer> ranks, Set<Metric> metrics, int biggerRank) {
        factsByValue.computeIfAbsent(value, k -> new ArrayList<>()).add(new Fact(ranks, metrics, biggerRank));
        return value;
    }

    /** 근거 줄의 이름이 가리키는 항목. 알 수 없으면 모든 항목으로 본다(근거 금액을 엉뚱한 항목으로 막지 않는다). */
    private static Set<Metric> metricsOf(String label) {
        Set<Metric> metrics = EnumSet.noneOf(Metric.class);
        if (label.contains("환수")) {
            metrics.add(Metric.CLAWBACK);
        }
        if (label.contains("이월")) {
            metrics.add(Metric.CARRY);
        }
        if (label.contains("순수령")) {
            metrics.add(Metric.NET);
        } else if (label.contains("수령")) {
            metrics.addAll(RECEIPT);
        }
        if (label.contains("한도")) {
            metrics.add(Metric.CAP_IMPACT);
            metrics.add(Metric.CAPPED);
        }
        return metrics.isEmpty() ? EnumSet.allOf(Metric.class) : metrics;
    }

    private Item find(int rank) {
        return ranking.items().stream().filter(i -> i.rank() == rank).findFirst().orElse(null);
    }

    private static String won(long amount) {
        return "%,d원".formatted(amount);
    }

    private static String error(String message) {
        return JSON.createObjectNode().put(ERROR, message).toString();
    }

    // ---- 검증 ----

    /** 도구로 얻지 않은 값, 상품·항목이 맞지 않는 금액, 반대 방향, 확정 추천이면 사유 코드를, 통과하면 null 을 돌려준다. */
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
        if (AnswerNumberGuard.hasKoreanAmount(normalized)) {
            return "KOREAN_AMOUNT";
        }
        // 이름 안의 숫자(상품명 '…2026' 등)와 1,200% 룰은 수치가 아니므로 같은 길이의 빈칸으로 가리고 숫자를 찾는다(위치를 유지한다).
        String masked = mask(TERM_1200, mask(names(), normalized));
        Matcher m = NUMBER.matcher(masked);
        while (m.find()) {
            String reason = verifyNumber(normalized, m.start(), m.group(1), m.group(2));
            if (reason != null) {
                return reason;
            }
        }
        return null;
    }

    private String verifyNumber(String text, int at, String number, String unit) {
        if (unit == null) {
            return "BARE_NUMBER";
        }
        if (!UNIT_WON.equals(unit) && !UNIT_PERCENT.equals(unit)) {
            return isRank(number) ? null : "UNKNOWN_RANK";
        }
        List<Fact> facts = factsByValue.get(number + unit);
        if (facts == null) {
            return "VALUE_NOT_FROM_TOOL";
        }
        int subject = nearestRankBefore(text, at);
        Set<Metric> metrics = nearestMetricsBefore(text, at);
        if (subject == 0 || metrics.isEmpty()) {
            return "VALUE_PAIR_MISMATCH";
        }
        boolean paired = false;
        for (Fact fact : facts) {
            if (fact.ranks().contains(subject) && fact.metrics().stream().anyMatch(metrics::contains)) {
                paired = true;
                if (directionHolds(sentenceAround(text, at), fact)) {
                    return null;
                }
            }
        }
        return paired ? "DIRECTION_MISMATCH" : "VALUE_PAIR_MISMATCH";
    }

    /** 금액 앞에서 가장 가까이 언급된 상품(순위 표기 또는 이름)의 순위. 없으면 0. 앞 문장에서 이어 말한 것도 센다. */
    private int nearestRankBefore(String text, int at) {
        int best = 0;
        int bestAt = -1;
        Matcher labels = RANK_LABEL.matcher(text);
        while (labels.find() && labels.start() < at) {
            int rank = Integer.parseInt(labels.group(1));
            if (find(rank) != null && labels.start() > bestAt) {
                bestAt = labels.start();
                best = rank;
            }
        }
        for (Item item : ranking.items()) {
            int named = lastIndexBefore(text, item.product(), at);
            named = Math.max(named, lastIndexBefore(text, item.insurer(), at));
            if (named > bestAt) {
                bestAt = named;
                best = item.rank();
            }
        }
        return best;
    }

    /** 같은 문장 안에서 금액 앞에 가장 가까이 쓴 항목 이름이 가리키는 항목들. 문장 안에 없으면 빈 집합. */
    private static Set<Metric> nearestMetricsBefore(String text, int at) {
        int sentenceStart = sentenceStart(text, at);
        String before = text.substring(sentenceStart, at);
        int bestEnd = -1;
        int bestLength = -1;
        Set<Metric> best = Set.of();
        for (MetricPhrase phrase : METRIC_PHRASES) {
            int found = before.lastIndexOf(phrase.text());
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

    private static int lastIndexBefore(String text, String name, int at) {
        if (name == null || name.isBlank()) {
            return -1;
        }
        String normalizedName = Normalizer.normalize(name, Normalizer.Form.NFKC);
        int found = text.lastIndexOf(normalizedName, Math.max(at - 1, 0));
        return found >= 0 && found < at ? found : -1;
    }

    private static int sentenceStart(String text, int at) {
        Matcher m = SENTENCE_END.matcher(text);
        int start = 0;
        while (m.find() && m.end() <= at) {
            start = m.end();
        }
        return start;
    }

    private static String sentenceAround(String text, int at) {
        int start = sentenceStart(text, at);
        Matcher m = SENTENCE_END.matcher(text);
        int end = text.length();
        while (m.find()) {
            if (m.start() >= at) {
                end = m.start();
                break;
            }
        }
        return text.substring(start, Math.max(start, end));
    }

    /** '가가 나보다 N원 많아요' 꼴에서 앞쪽(가)이 서버 값이 큰 쪽인지 본다. 꼴을 알아볼 수 없으면 통과시킨다. */
    private boolean directionHolds(String sentence, Fact fact) {
        if (fact.biggerRank() == 0) {
            return true;
        }
        int pivot = sentence.indexOf("보다");
        if (pivot <= 0) {
            return true;
        }
        // '보수적 순수령액'의 '적'이 '적어요'로 읽히지 않게 항목 이름은 지우고 서술어만 본다.
        String predicate = sentence.substring(pivot).replace("보수적", " ");
        boolean saysBigger = BIGGER_WORDS.stream().anyMatch(predicate::contains);
        boolean saysSmaller = SMALLER_WORDS.stream().anyMatch(predicate::contains);
        if (saysBigger == saysSmaller) {
            return true;
        }
        int subject = subjectBeforeThan(sentence.substring(0, pivot), fact.ranks());
        if (subject == 0) {
            return true;
        }
        return saysBigger == (subject == fact.biggerRank());
    }

    /** 'A가 B보다' 에서 A. 보다 앞에 두 순위가 모두 나오면 먼저 나온 쪽이 주어다. 하나뿐이면 어느 쪽인지 알 수 없어 0. */
    private int subjectBeforeThan(String part, List<Integer> candidates) {
        int first = 0;
        int firstAt = Integer.MAX_VALUE;
        int found = 0;
        for (int rank : candidates) {
            int at = firstMention(part, rank, find(rank));
            if (at >= 0) {
                found++;
                if (at < firstAt) {
                    firstAt = at;
                    first = rank;
                }
            }
        }
        return found >= 2 ? first : 0;
    }

    private static int firstMention(String part, int rank, Item item) {
        int best = part.indexOf(rank + "위");
        if (item != null) {
            for (String name : new String[] {item.product(), item.insurer()}) {
                int at = name == null ? -1 : part.indexOf(name);
                if (at >= 0 && (best < 0 || at < best)) {
                    best = at;
                }
            }
        }
        return best;
    }

    private boolean isRank(String number) {
        try {
            return find(Integer.parseInt(number)) != null;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private List<String> names() {
        List<String> names = new ArrayList<>();
        for (Item item : ranking.items()) {
            for (String name : new String[] {item.insurer(), item.product()}) {
                if (name != null && !name.isBlank()) {
                    names.add(Normalizer.normalize(name, Normalizer.Form.NFKC));
                }
            }
        }
        return names;
    }

    /** 이름들을 같은 길이의 빈칸으로 바꾼다. */
    private static String mask(List<String> names, String text) {
        String result = text;
        for (String name : names) {
            result = result.replace(name, " ".repeat(name.length()));
        }
        return result;
    }

    private static String mask(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, " ".repeat(m.end() - m.start()));
        }
        m.appendTail(out);
        return out.toString();
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
