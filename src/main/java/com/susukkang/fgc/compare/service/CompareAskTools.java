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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 설명 : 상품 비교 챗봇이 모델에 열어 주는 서버 도구와, 모델 답변을 도구 결과로 검증하는 규칙(FUN-079, #889).
 * 도구는 서버가 계산한 순위 결과의 값만 돌려주고, 돌려준 금액마다 '어느 순위의 어느 항목인지'를 기억한다.
 * 검증은 답변의 모든 금액·비율이 도구가 준 값이고, 같은 문장에 그 값의 상품(순위·이름)과 항목 이름이 함께 있는지,
 * 두 상품의 차이를 말할 때 많고 적음의 방향이 서버 값과 같은지를 본다. 요청 하나에 한 번 만들고 버린다(상태를 가진다).
 */
final class CompareAskTools {

    static final String GET_ITEM = "get_item";
    static final String COMPARE = "compare";
    static final String EXPLAIN_TERM = "explain_term";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NET = "보수적 순수령액";
    private static final String CAPPED = "한도 반영 수령 예상액";
    private static final String EXPECTED = "한도 적용 전 수령 예상액";
    private static final String CLAWBACK = "환수 노출액";
    private static final String CARRY = "이월 예정액";
    private static final String CAP_IMPACT = "한도 영향";
    private static final String ERROR = "error";
    private static final String UNIT_WON = "원";
    private static final String UNIT_PERCENT = "%";
    private static final Pattern NUMBER = Pattern.compile("(\\d[\\d,]*+(?:\\.\\d++)?)\\s*+(원|%|순위|위|등(?!급))?", Pattern.CANON_EQ);
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

    /** 도구가 돌려준 값 하나가 가리키는 대상. ranks 는 값이 속한 순위(차이는 두 순위), biggerRank 는 차이에서 값이 큰 쪽(같으면 0). */
    private record Fact(List<Integer> ranks, List<String> metricWords, int biggerRank) {
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
                row.put("금액", remember(won(e.value()), List.of(item.rank()), wordsOf(e.label()), 0));
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
        putDiff(diff, NET, a, b, a.conservativeNet(), b.conservativeNet());
        putDiff(diff, CAPPED, a, b, a.cappedReceipt(), b.cappedReceipt());
        putDiff(diff, CLAWBACK, a, b, a.clawbackExposure(), b.clawbackExposure());
        putDiff(diff, CARRY, a, b, a.carryover(), b.carryover());
        return node.toString();
    }

    private void putDiff(ObjectNode diff, String metric, Item a, Item b, long av, long bv) {
        ObjectNode row = diff.putObject(metric);
        int bigger = av == bv ? 0 : av > bv ? a.rank() : b.rank();
        row.put("금액", remember(won(Math.abs(av - bv)), List.of(a.rank(), b.rank()), wordsOf(metric), bigger));
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
        node.put(NET, remember(won(item.conservativeNet()), self, wordsOf(NET), 0));
        node.put(CAPPED, remember(won(item.cappedReceipt()), self, wordsOf(CAPPED), 0));
        node.put(EXPECTED, remember(won(item.expectedReceipt()), self, wordsOf(EXPECTED), 0));
        node.put(CLAWBACK, remember(won(item.clawbackExposure()), self, wordsOf(CLAWBACK), 0));
        node.put(CARRY, remember(won(item.carryover()), self, wordsOf(CARRY), 0));
        if (item.capImpactPct() != null) {
            node.put(CAP_IMPACT, remember("%.1f%%".formatted(item.capImpactPct()), self, wordsOf(CAP_IMPACT), 0));
        }
        return node;
    }

    /** 도구가 돌려주는 값을 기억하고 그대로 돌려준다. */
    private String remember(String value, List<Integer> ranks, List<String> metricWords, int biggerRank) {
        factsByValue.computeIfAbsent(value, k -> new ArrayList<>()).add(new Fact(ranks, metricWords, biggerRank));
        return value;
    }

    private static List<String> wordsOf(String metric) {
        List<String> words = new ArrayList<>();
        for (String word : List.of("순수령", "수령", "한도", "환수", "이월")) {
            if (metric.contains(word)) {
                words.add(word);
            }
        }
        return words.isEmpty() ? List.of(metric) : words;
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
        for (String sentence : SENTENCE_END.split(normalized)) {
            String reason = verifySentence(sentence);
            if (reason != null) {
                return reason;
            }
        }
        return null;
    }

    private String verifySentence(String sentence) {
        String bare = TERM_1200.matcher(withoutNames(sentence)).replaceAll(" ");
        Matcher m = NUMBER.matcher(bare);
        while (m.find()) {
            String unit = m.group(2);
            String number = m.group(1);
            if (unit == null) {
                return "BARE_NUMBER";
            }
            if (UNIT_WON.equals(unit) || UNIT_PERCENT.equals(unit)) {
                String reason = verifyValue(sentence, number + unit);
                if (reason != null) {
                    return reason;
                }
            } else if (!isRank(number)) {
                return "UNKNOWN_RANK";
            }
        }
        return null;
    }

    private String verifyValue(String sentence, String value) {
        List<Fact> facts = factsByValue.get(value);
        if (facts == null) {
            return "VALUE_NOT_FROM_TOOL";
        }
        for (Fact fact : facts) {
            if (mentionsAny(sentence, fact.ranks()) && fact.metricWords().stream().anyMatch(sentence::contains)
                    && directionHolds(sentence, fact)) {
                return null;
            }
        }
        return facts.stream().noneMatch(f -> mentionsAny(sentence, f.ranks()) && f.metricWords().stream().anyMatch(sentence::contains))
                ? "VALUE_PAIR_MISMATCH" : "DIRECTION_MISMATCH";
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
            Item item = find(rank);
            int at = firstMention(part, rank, item);
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

    private static int mentionIndex(String part, Item item) {
        return Math.max(item.product() == null ? -1 : part.lastIndexOf(item.product()),
                item.insurer() == null ? -1 : part.lastIndexOf(item.insurer()));
    }

    private boolean mentionsAny(String sentence, List<Integer> ranks) {
        for (int rank : ranks) {
            Item item = find(rank);
            if (sentence.contains(rank + "위") || (item != null && mentionIndex(sentence, item) >= 0)) {
                return true;
            }
        }
        return false;
    }

    private boolean isRank(String number) {
        try {
            int rank = Integer.parseInt(number);
            return find(rank) != null;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 이름 안의 숫자(상품명 '…2026' 등)는 수치가 아니므로 지우고 본다. */
    private String withoutNames(String sentence) {
        String result = sentence;
        for (Item item : ranking.items()) {
            for (String name : new String[] {item.insurer(), item.product()}) {
                if (name != null && !name.isBlank()) {
                    result = result.replace(Normalizer.normalize(name, Normalizer.Form.NFKC), " ");
                }
            }
        }
        return result;
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
