package com.susukkang.fgc.compare.service;

import com.susukkang.fgc.compare.dto.CompareRankingResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 가 숫자를 직접 쓰지 않고 자리표시자({@code {r3.net}})로 문장을 쓰면, 서버가 검증된 값으로 채운다(#853).
 * 문장은 모델이 자연스럽게 쓰고, 금액·순위는 항상 서버 값이다. 목록에 없는 자리표시자가 있으면 답변을 버린다.
 */
final class AnswerSlots {
    private static final Pattern SLOT = Pattern.compile("\\{([A-Za-z0-9_.]{1,20})\\}");

    private AnswerSlots() {
    }

    /** 순위별 값과, 질문이 두 순위를 가리키면 그 둘의 차이(절댓값). */
    static Map<String, String> values(CompareRankingResponse ranking, List<Integer> asked) {
        Map<String, String> values = new LinkedHashMap<>();
        for (CompareRankingResponse.Item item : ranking.items()) {
            String key = "r" + item.rank() + ".";
            values.put(key + "rank", item.rank() + "위");
            values.put(key + "net", won(item.conservativeNet()));
            values.put(key + "cap", won(item.cappedReceipt()));
            values.put(key + "expected", won(item.expectedReceipt()));
            values.put(key + "clawback", won(item.clawbackExposure()));
            values.put(key + "carry", won(item.carryover()));
        }
        if (asked.size() == 2) {
            CompareRankingResponse.Item a = ranking.items().get(asked.get(0) - 1);
            CompareRankingResponse.Item b = ranking.items().get(asked.get(1) - 1);
            values.put("diff.net", won(Math.abs(a.conservativeNet() - b.conservativeNet())));
            values.put("diff.cap", won(Math.abs(a.cappedReceipt() - b.cappedReceipt())));
            values.put("diff.clawback", won(Math.abs(a.clawbackExposure() - b.clawbackExposure())));
        }
        return values;
    }

    /** LLM 데이터에 붙이는 사용 안내. 값은 주지 않고 이름과 뜻만 준다(값은 순위 결과에 이미 있다). */
    static String describe(Map<String, String> values) {
        if (values.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n[자리표시자]\n");
        sb.append("- 금액·순위는 숫자로 쓰지 말고 아래 자리표시자를 중괄호째 그대로 쓴다. 예: {r2.net}\n");
        sb.append("- 형식: r<순위번호>.rank(순위) / net(보수적 순수령액) / cap(한도 반영 수령 예상액) / expected(수령 예상액)")
                .append(" / clawback(환수 노출액) / carry(이월 예정액)\n");
        sb.append("- 쓸 수 있는 순위 번호: ").append(rankNumbers(values)).append('\n');
        if (values.containsKey("diff.net")) {
            sb.append("- 질문이 가리킨 두 상품의 차이(금액만, 많고 적음은 말로): {diff.net}(순수령액) {diff.cap}(한도 반영 수령 예상액) {diff.clawback}(환수 노출액)\n");
        }
        return sb.toString();
    }

    private static String rankNumbers(Map<String, String> values) {
        return values.keySet().stream().filter(k -> k.endsWith(".rank")).map(k -> k.substring(1, k.indexOf('.')))
                .reduce((a, b) -> a + ", " + b).orElse("");
    }

    static boolean uses(String text) {
        return SLOT.matcher(text).find();
    }

    /** 검증용: 자리표시자를 지운 문장. 남은 글에 숫자가 있으면 모델이 직접 쓴 것이다. */
    static String strip(String text) {
        return SLOT.matcher(text).replaceAll(" ");
    }

    /** 자리표시자를 서버 값으로 채운다. 목록에 없는 이름이 하나라도 있으면 비어 있다. */
    static Optional<String> render(String text, Map<String, String> values) {
        Matcher m = SLOT.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            if (value == null) {
                return Optional.empty();
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return Optional.of(out.toString());
    }

    private static String won(long amount) {
        return "%,d원".formatted(amount);
    }
}
