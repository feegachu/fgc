package com.susukkang.fgc.compare.service;

import com.susukkang.fgc.ai.service.LlmGateway;
import com.susukkang.fgc.ai.service.LlmGateway.LlmRequest;
import com.susukkang.fgc.common.code.AiCallPurpose;
import com.susukkang.fgc.compare.dto.CompareRankingResponse;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 설명 : 서버가 확정한 순위 결과를 LLM 으로 풀어 쓴 요약 문장을 만든다(FUN-079, SER-013).
 * LLM 은 문장만 쓰고 순위·금액은 바꾸지 못한다. 입력에 없는 숫자나 단정 표현이 나오면 버리고 호출자가 규칙형 문장으로 대체한다.
 * 호출 실패·키 없음·차단은 빈 값으로 돌려준다(게이트웨이가 ai_call_log 를 남긴다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompareSummaryService {

    static final String INSTRUCTIONS = """
            너는 보험대리점 설계사에게 상품 비교 순위를 풀어 설명하는 도우미다.
            input 의 순위·금액은 서버가 계산한 확정값이다. 그 값만 그대로 쓰고 새 숫자를 만들거나 계산하지 마라.
            2~3문장, 존댓말(~요 체)로 쓴다. 1위 상품과 그 이유, 한도·환수 관련 유의점이 있으면 한 가지를 짚는다.
            최종 선택은 설계사가 한다는 뜻을 담고 '반드시·무조건·보장' 같은 단정 표현은 쓰지 않는다.
            금액은 입력에 적힌 표기(예: 1,234,000원) 그대로 쓴다.
            """;

    private static final int MAX_LENGTH = 400;
    private static final Pattern NUMBER = Pattern.compile("[0-9][0-9,]*(?:[.][0-9]+)?");
    private static final Pattern FORBIDDEN = Pattern.compile("반드시|무조건|보장합니다|확정입니다");

    private final LlmGateway llmGateway;

    /** LLM 이 돌려주는 구조화 출력. */
    public static class Output {
        public String summary;
    }

    public Optional<String> summarize(CompareRankingResponse ranking) {
        if (ranking.items().isEmpty()) {
            return Optional.empty();
        }
        String data = describe(ranking);
        Output output;
        try {
            output = llmGateway.call(LlmRequest.builder()
                    .purpose(AiCallPurpose.PICO_EXPLAIN)
                    .instructions(INSTRUCTIONS)
                    .data(data)
                    .relatedEntityType("COMPARE_RANK")
                    .build(), Output.class);
        } catch (RuntimeException e) {
            // 게이트웨이가 이미 사유를 ai_call_log 에 남겼다. 호출자는 규칙형 문장으로 계속한다.
            return Optional.empty();
        }
        String text = output == null || output.summary == null ? "" : output.summary.strip();
        if (!isValid(text, data, ranking.items().get(0))) {
            log.warn("LLM 요약이 검증을 통과하지 못해 규칙형 문장으로 대체합니다(categoryCode={}).", ranking.categoryCode());
            return Optional.empty();
        }
        return Optional.of(text);
    }

    /** LLM 에 보내는 데이터: 계약자 개인정보 없이 카테고리·순위·금액·경고만 담는다. */
    static String describe(CompareRankingResponse ranking) {
        StringBuilder sb = new StringBuilder();
        sb.append("카테고리: ").append(ranking.categoryLabel()).append('\n');
        sb.append("기준일: ").append(ranking.asOf()).append('\n');
        sb.append("순위 기준: 보수적 순수령액 = 한도 반영 수령 예상액 - 환수 노출액\n");
        for (CompareRankingResponse.Item item : ranking.items()) {
            sb.append("%d위 %s %s | 수령 예상 %,d원 | 한도 반영 %,d원 | 환수 노출 %,d원 | 보수적 순수령액 %,d원"
                    .formatted(item.rank(), item.insurer(), item.product(), item.expectedReceipt(), item.cappedReceipt(),
                            item.clawbackExposure(), item.conservativeNet()));
            if (item.carryover() > 0) {
                sb.append(" | 한도 초과 이월 %,d원".formatted(item.carryover()));
            }
            sb.append('\n');
        }
        if (!ranking.warnings().isEmpty()) {
            sb.append("경고 코드: ").append(String.join(", ", ranking.warnings())).append('\n');
        }
        return sb.toString();
    }

    /** 입력에 없는 숫자·단정 표현·너무 긴 글은 걸러낸다. 1위 상품 이름이 빠진 글도 쓰지 않는다. */
    static boolean isValid(String text, String data, CompareRankingResponse.Item top) {
        if (text.isBlank() || text.length() > MAX_LENGTH || FORBIDDEN.matcher(text).find()) {
            return false;
        }
        if (!text.contains(top.product()) && !text.contains(top.insurer())) {
            return false;
        }
        Set<String> allowed = numbers(data);
        return allowed.containsAll(numbers(text));
    }

    private static Set<String> numbers(String text) {
        Set<String> found = new HashSet<>();
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            found.add(m.group().replaceAll("[,.]+$", "").replace(",", ""));
        }
        return found;
    }

}
