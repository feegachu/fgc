package com.susukkang.fgc.compare.service;

import java.text.Normalizer;
import java.util.Collection;
import java.util.regex.Pattern;

/**
 * 설명 : AI 문장에는 숫자를 쓰지 못하게 한다(FUN-079, 인터페이스정의서 §4-4-6). 금액·순위·비율은 서버 값으로 만든 문장만 보여 준다.
 * 숫자를 근거와 대조하는 방식은 다른 항목·다른 상품의 값을 옮겨 쓰거나 항목 이름을 빼는 표현을 다 막지 못해서(PR #739 리뷰),
 * 숫자 자체를 받지 않는다. 전각 숫자는 정규화해 같은 숫자로 보고, 한글 숫자로 쓴 금액·비율도 거절한다.
 * 용어 이름 '1,200%'와 서버가 준 보험사·상품 이름 안의 숫자만 예외다.
 */
final class AnswerNumberGuard {

    /** 용어 이름(1,200% 룰). 금액·순위가 아니라서 허용한다. */
    private static final Pattern TERM = Pattern.compile("1,?200\\s*%");
    private static final Pattern DIGIT = Pattern.compile("\\p{Nd}");
    /** 구백만원·삼십퍼센트처럼 한글 숫자로 쓴 금액·비율. 자릿수 글자(십·백·천·만·억·조)가 있으면 띄어 써도 잡는다. */
    private static final Pattern KOREAN_AMOUNT = Pattern.compile("[영일이삼사오육칠팔구십백천만억조]++(?:원|%|퍼센트|프로)"
            + "|[영일이삼사오육칠팔구]*+[십백천만억조][영일이삼사오육칠팔구십백천만억조]*+\\s++(?:원|%|퍼센트|프로)", Pattern.CANON_EQ);

    private AnswerNumberGuard() {
    }

    /** names 는 서버가 준 보험사·상품 이름이다. 이름 안의 숫자(예: 상품명 '…2026')는 수치가 아니므로 지우고 본다. */
    static boolean allowed(String text, Collection<String> names) {
        String normalized = normalize(text);
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                normalized = normalized.replace(normalize(name), " ");
            }
        }
        normalized = TERM.matcher(normalized).replaceAll(" ");
        return !DIGIT.matcher(normalized).find() && !KOREAN_AMOUNT.matcher(normalized).find();
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC);
    }
}
