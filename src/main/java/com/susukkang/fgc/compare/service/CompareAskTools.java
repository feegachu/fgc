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
    private static final Pattern SUPERLATIVE = Pattern.compile("가장\\s*(큰|크|커|높|많|작은|작|낮|적)", Pattern.CANON_EQ);
    /** '한도에 걸렸다·도달했다·초과했다' 처럼 한도에 닿았다는 주장. 조사가 없는 '한도 초과 시'(설명)는 잡지 않는다. */
    private static final Pattern CAP_HIT = Pattern.compile("한도(?:[에를]|까지)\\s*(?:걸|도달|초과|[넘닿])(?!는|하는)", Pattern.CANON_EQ);
    /** 상품을 짚지 않은 문장은 "모든/전체 상품"처럼 전체를 말할 때만 상태 주장으로 본다(용어 설명의 "한도를 넘는 금액은…"은 제외). */
    private static final Pattern ALL_SCOPE = Pattern.compile("모두|모든|전부|전체", Pattern.CANON_EQ);
    private static final Pattern CONDITIONAL = Pattern.compile("하면|이면|으면|라면|경우|만약|시\\s|시에", Pattern.CANON_EQ);
    /** 한도 도달 표현. 한 문장에서 긍정과 부정이 섞이면('1위는 걸렸고 2위는 걸리지 않았어요') 주장이 섞여 있어 판단하지 않는다. */
    private static final Pattern CAP_VERB = Pattern.compile("걸[리려렸린]|도달|초과|[넘닿]", Pattern.CANON_EQ);
    /** 답에 서버 내부 도구를 드러내는 말('도구를 통해 제공하며', 'get_item'). 설계사에게는 의미 없는 내부 구현이라 답으로 내보내지 않는다. '보조 도구예요' 같은 일반 용례는 막지 않는다. */
    private static final List<Pattern> TOOL_MENTIONS = List.of(
            Pattern.compile("(?:내부|서버)\\s*도구|함수\\s*호출", Pattern.CANON_EQ),
            Pattern.compile("도구\\s*(?:[를로가값]|호출|결과)", Pattern.CANON_EQ),
            Pattern.compile("get_item|explain_term|compare\\s*\\(", Pattern.CANON_EQ));
    /** 도달 표현과 부정 사이에 끼는 활용('초과되지', '넘어서지', '걸려 있지', '초과하지'). */
    private static final Pattern NEGATION_TAIL = Pattern.compile("^(?:[하한되돼]|[어아해]서|\\s*있)?", Pattern.CANON_EQ);
    private static final List<Pattern> NEGATIONS = List.of(
            Pattern.compile("^[지진]도?는?\\s*(?:[않못]|아니)", Pattern.CANON_EQ),
            Pattern.compile("^[은는]?\\s*(?:적|일|경우|상태)[이가은는도]?\\s*(?:전혀\\s*)?(?:없|아니)", Pattern.CANON_EQ));

    private static final Pattern ANY_NEGATION = Pattern.compile("[않없못]|아니", Pattern.CANON_EQ);
    /** '한도를 넘어 받지 못한 금액이 있어요'처럼 못 받은 금액이 있다는 말. 부정어가 있어도 한도에 걸렸다는 긍정 주장이다. */
    private static final Pattern UNRECEIVED_AMOUNT_EXISTS = Pattern.compile("[못않](?:(?![않없못]|아니)[^,.!?]){0,10}?(?:금액|수수료|분)[이은]?\\s*있", Pattern.CANON_EQ);
    private static final int POSITIVE = 0;
    private static final int NEGATED = 1;
    private static final int UNCLEAR = 2;

    /**
     * 도달 표현 뒤의 말이 그 표현을 긍정하는지 부정하는지. 바로 뒤에서 부정이 확인되면 부정, 구간에 부정어가 하나도 없으면 긍정이다.
     * 부정어가 있는데 바로 뒤가 아니면(활용형을 못 알아본 경우 포함) 긍정인지 부정인지 정규식으로 가를 수 없어 판단 불가로 둔다.
     */
    private static int polarityAfter(String rest) {
        if (negatedRightAfter(rest)) {
            return NEGATED;
        }
        // 못 받은 금액이 있다는 말은 긍정이지만, 그 말을 걷어 내고도 부정어가 남으면 다른 부정이 있는 것이라 가를 수 없다.
        String stripped = UNRECEIVED_AMOUNT_EXISTS.matcher(rest).replaceAll("");
        return ANY_NEGATION.matcher(stripped).find() ? UNCLEAR : POSITIVE;
    }

    /** 도달 표현 바로 뒤에서 그 표현을 부정하는지. 뒤쪽 다른 서술어의 '못'·'없'은 도달 표현의 부정이 아니다. */
    private static boolean negatedRightAfter(String rest) {
        Matcher tail = NEGATION_TAIL.matcher(rest);
        String remainder = tail.find() ? rest.substring(tail.end()) : rest;
        return NEGATIONS.stream().anyMatch(pattern -> pattern.matcher(remainder).find());
    }
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
        if (TOOL_MENTIONS.stream().anyMatch(pattern -> pattern.matcher(normalized).find())) {
            return "TOOL_MENTION";
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
            String reason = verifyRankNames(sentence);
            if (reason == null) {
                reason = verifySuperlative(sentence);
            }
            if (reason == null) {
                reason = verifyLeadClaim(sentence);
            }
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

    /** 항목 없이 '가장 앞섰다'고만 쓴 문장('A가 가장 앞섰어요'). 순위는 종합 결과라서 이렇게 말할 수 있는 상품은 1위뿐이다(#919 C-R1). */
    private static final Pattern LEAD_CLAIM = Pattern.compile("(?:가장|제일|맨)(?:\\s*먼저)?\\s*(?:앞섰|앞서|앞선|우수|상위)", Pattern.CANON_EQ);

    /** 두 갈래(하지 않았다 / 아니다)로 나눠 한 식의 복잡도를 줄이고, 소유 수량자로 되돌림(backtracking)을 막는다. */
    private static final List<Pattern> LEAD_NEGATIONS = List.of(
            Pattern.compile("^[하한]?[지진]도?는?\\s*+[않못]", Pattern.CANON_EQ),
            Pattern.compile("^[하한]?\\s*+(?:상품|것|쪽|곳)?+[이가은는]?+\\s*+아니", Pattern.CANON_EQ));

    private String verifyLeadClaim(String sentence) {
        if (!LEAD_CLAIM.matcher(sentence).find() || CONDITIONAL.matcher(sentence).find() || sentence.contains("다면")) {
            return null;
        }
        Matcher lead = LEAD_CLAIM.matcher(sentence);
        lead.find();
        // 환수·이월 같은 항목을 앞에 붙인 말은 그 항목만의 이야기라 항목별 검사(최상급)에 맡긴다.
        if (!metricsIn(sentence.substring(0, lead.start())).isEmpty()) {
            return null;
        }
        Set<Integer> mentioned = ranksMentioned(sentence);
        int top = ranking.items().stream().mapToInt(Item::rank).min().orElse(1);
        if (mentioned.size() != 1) {
            return null;
        }
        // '가장 앞서지 않았어요', '가장 앞선 상품이 아니에요'는 1위가 아닐 때만 맞다.
        String afterLead = sentence.substring(lead.end());
        boolean negated = LEAD_NEGATIONS.stream().anyMatch(pattern -> pattern.matcher(afterLead).find());
        return mentioned.contains(top) == negated ? "LEAD_MISMATCH" : null;
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
        if (mentioned.isEmpty() && !ALL_SCOPE.matcher(sentence).find()) {
            return null;
        }
        // 도달 표현마다 뒤따르는 부분에서 부정을 본다. 같은 주장의 반복은 함께 검사하고, 긍정과 부정이 섞이면 판단하지 않는다.
        Set<Integer> polarities = new HashSet<>();
        Matcher verb = CAP_VERB.matcher(sentence);
        List<int[]> verbs = new ArrayList<>();
        while (verb.find()) {
            verbs.add(new int[] {verb.start(), verb.end()});
        }
        for (int k = 0; k < verbs.size(); k++) {
            int to = k + 1 < verbs.size() ? verbs.get(k + 1)[0] : sentence.length();
            polarities.add(polarityAfter(sentence.substring(verbs.get(k)[1], to)));
        }
        // 긍정인지 부정인지 가를 수 없으면 맞는 말인지 알 수 없으므로 거절한다(서버 문장으로 대체되는 안전한 쪽).
        if (polarities.contains(UNCLEAR)) {
            return "CAP_STATE_MISMATCH";
        }
        if (polarities.size() != 1) {
            return null;
        }
        boolean negated = polarities.iterator().next() == NEGATED;
        // 걸렸다는 말은 말한 상품 모두에 이월이 있어야 맞고, 걸리지 않았다는 말은 하나도 없어야 맞다.
        boolean holds = negated ? scope.stream().noneMatch(item -> item.carryover() > 0)
                : scope.stream().allMatch(item -> item.carryover() > 0);
        return holds ? null : "CAP_STATE_MISMATCH";
    }

    /** 이름(상품·보험사)이 글에서 차지한 곳. 끝은 포함하지 않는다. */
    private record Span(int rank, int at, int end) {
    }

    private static final Pattern AFTER_PARTICLE = Pattern.compile("^\\s*(?:은|는|이|가|인|의)?\\s*");
    /** 표기 바로 앞에서 떼는 조사 한 글자와 공백(정규식 \s 와 같은 글자). */
    private static final String BEFORE_PARTICLES = "은는이가도만인";
    private static final String SPACES = " \t\n\u000B\f\r";
    /** '1위예요', '1위이고', '1위,'처럼 순위 표기로 절이 끝나는 꼴. '2위보다'는 비교 기준이라 여기에 들지 않는다. */
    private static final Pattern CLAUSE_END = Pattern.compile("^\\s*(?:이고|이며|이면서|이에요|예요|입니다|이었|였|이야|야|이다|이지만|이라|인데|이므로|이니|이나|[,.!?]|$)");

    /**
     * 순위 표기('1위')에 바로 붙은 상품 이름이 그 순위의 상품이어야 한다. 집합이 아니라 짝을 본다.
     * 표기 바로 뒤('1위는 A', '1위인 A')와, 표기로 절이 끝날 때 바로 앞('A가 1위예요')의 이름만 짝으로 본다. 접속 표현이 끼면 짝이 아니다.
     * 표기 사이에 이름이 없는 연속 표기('1위와 2위는 A와 B')는 한 묶음으로 보고 순서대로 짝짓고, 묶음보다 이름이 적으면 판단하지 않는다.
     */
    private String verifyRankNames(String sentence) {
        String normalized = Normalizer.normalize(sentence, Normalizer.Form.NFKC);
        List<int[]> labels = new ArrayList<>();
        Matcher label = RANK_LABEL.matcher(normalized);
        while (label.find()) {
            int rank = Integer.parseInt(label.group(1));
            if (find(rank) != null) {
                labels.add(new int[] {rank, label.start(), label.end()});
            }
        }
        if (labels.isEmpty()) {
            return null;
        }
        List<Span> spans = nameSpans(normalized);
        int i = 0;
        while (i < labels.size()) {
            int j = i;
            while (j + 1 < labels.size() && namesBetween(spans, labels.get(j)[2], labels.get(j + 1)[1]).isEmpty()) {
                j++;
            }
            int size = j - i + 1;
            String reason = size == 1 ? checkSingleLabel(normalized, spans, labels.get(i), j + 1 < labels.size() ? labels.get(j + 1)[1] : normalized.length())
                    : checkEnumeration(spans, labels.subList(i, j + 1), j + 1 < labels.size() ? labels.get(j + 1)[1] : normalized.length());
            if (reason != null) {
                return reason;
            }
            i = j + 1;
        }
        return null;
    }

    /** 표기와 이름 사이에 이 말이 끼면 같은 대상을 말한 것이 아니라 다른 절이다('1위이고 튼튼건강이 2위', '1위는 작지만 B는'). */
    private static final Pattern CLAUSE_BREAK = Pattern.compile("이고|이며|이면서|지만|반면|비해|하고|하며|인데|이므로|이니|이라|이나|라\\s|고\\s|며\\s|서\\s|[,;.!?]");
    /** 이름 바로 뒤에 이 말이 오면 그 이름은 비교·나열의 상대라서 순위의 주인이 아니다('1위는 … B보다 앞서요'). */
    private static final Pattern NAME_IS_PARTNER = Pattern.compile("^\\s*(?:보다|와|과|랑|및)");
    private static final int MAX_FILLER_GAP = 16;

    private static String checkSingleLabel(String normalized, List<Span> spans, int[] label, int regionEnd) {
        Set<Integer> candidates = new HashSet<>();
        Matcher after = AFTER_PARTICLE.matcher(normalized.substring(label[2]));
        if (after.find()) {
            int at = label[2] + after.end();
            spans.stream().filter(span -> span.at() == at).forEach(span -> candidates.add(span.rank()));
        }
        if (CLAUSE_END.matcher(normalized.substring(label[2])).find()) {
            int end = nameEndBefore(normalized, label[1]);
            spans.stream().filter(span -> span.end() == end).forEach(span -> candidates.add(span.rank()));
        }
        if (candidates.isEmpty()) {
            // 바로 붙은 이름이 없으면('1위 상품은 A', '1위는 바로 A') 같은 절 안의 첫 이름과 짝짓는다. 접속 표현이 끼거나 비교 상대로 쓰인 이름은 짝이 아니다.
            spans.stream().filter(span -> span.at() >= label[2] && span.end() <= regionEnd).min((x, y) -> Integer.compare(x.at(), y.at()))
                    .filter(span -> span.at() - label[2] <= MAX_FILLER_GAP)
                    .filter(span -> !CLAUSE_BREAK.matcher(normalized.substring(label[2], span.at())).find())
                    .filter(span -> !NAME_IS_PARTNER.matcher(normalized.substring(span.end())).find())
                    .ifPresent(span -> candidates.add(span.rank()));
        }
        return !candidates.isEmpty() && !candidates.contains(label[0]) ? "RANK_MISMATCH" : null;
    }

    /**
     * 표기 바로 앞에서 공백, 조사 한 글자, 공백을 거꾸로 뗀 위치. 'A가 1위예요'에서 A가 끝나는 곳이다.
     * 정규식 \s*(조사)?\s*$ 의 가장 왼쪽 일치 시작과 같다. 그 정규식은 공백이 길면 시작 위치마다 다시 시도해 느려졌다(SonarCloud S8786).
     */
    private static int nameEndBefore(String text, int labelStart) {
        int end = skipSpacesBackward(text, labelStart);
        if (end > 0 && BEFORE_PARTICLES.indexOf(text.charAt(end - 1)) >= 0) {
            end = skipSpacesBackward(text, end - 1);
        }
        return end;
    }

    private static int skipSpacesBackward(String text, int end) {
        while (end > 0 && SPACES.indexOf(text.charAt(end - 1)) >= 0) {
            end--;
        }
        return end;
    }

    private static String checkEnumeration(List<Span> spans, List<int[]> group, int regionEnd) {
        List<Span> names = namesBetween(spans, group.get(group.size() - 1)[2], regionEnd);
        if (names.size() < group.size()) {
            return null;
        }
        for (int k = 0; k < group.size(); k++) {
            if (names.get(k).rank() != group.get(k)[0]) {
                return "RANK_MISMATCH";
            }
        }
        return null;
    }

    /** from~to 사이에 통째로 들어 있는 이름을 나온 순서대로. 같은 순위는 한 번만 담는다. */
    private static List<Span> namesBetween(List<Span> spans, int from, int to) {
        List<Span> inside = new ArrayList<>(spans.stream().filter(span -> span.at() >= from && span.end() <= to).toList());
        inside.sort((x, y) -> Integer.compare(x.at(), y.at()));
        Set<Integer> seen = new HashSet<>();
        return inside.stream().filter(span -> seen.add(span.rank())).toList();
    }

    /** 글에서 상품·보험사 이름이 나온 곳(보험사는 결과 안에 그 보험사 상품이 하나뿐일 때만). */
    private List<Span> nameSpans(String normalized) {
        List<Span> spans = new ArrayList<>();
        for (ProductNames.Mention mention : ProductNames.find(ranking.items(), normalized)) {
            spans.add(new Span(mention.rank(), mention.at(), mention.end()));
        }
        for (Item item : ranking.items()) {
            boolean unique = ranking.items().stream().filter(o -> same(o.insurer(), item.insurer())).count() == 1;
            int at = unique ? indexOf(normalized, item.insurer()) : -1;
            if (at >= 0) {
                spans.add(new Span(item.rank(), at, at + Normalizer.normalize(item.insurer(), Normalizer.Form.NFKC).length()));
            }
        }
        return spans;
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
        int best = nameMention(part, item);
        Matcher label = RANK_LABEL.matcher(part);
        while (label.find()) {
            if (Integer.parseInt(label.group(1)) == item.rank()) {
                return earliest(best, label.start());
            }
        }
        return best;
    }

    /** 순위 표기를 빼고 이름(상품·보험사)만으로 찾은 첫 위치. 없으면 -1. */
    private int nameMention(String part, Item item) {
        int product = ProductNames.find(ranking.items(), part).stream()
                .filter(m -> m.rank() == item.rank()).mapToInt(ProductNames.Mention::at).findFirst().orElse(-1);
        boolean insurerUnique = ranking.items().stream().filter(o -> same(o.insurer(), item.insurer())).count() == 1;
        int insurer = insurerUnique ? indexOf(part, item.insurer()) : -1;
        return earliest(product, insurer);
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
