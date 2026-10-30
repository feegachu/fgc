package com.susukkang.fgc.compare.service;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 모델이 쓴 설명의 숫자를 서버 근거와 대조한다. 숫자가 근거 어딘가에 있다는 것만으로는 부족해서(다른 항목의 값을
 * 재사용할 수 있다) 금액 앞의 항목 이름과 값이 근거의 같은 항목에 묶여 있는지도 본다.
 *
 * <p>전각 숫자는 정규화해서 같은 숫자로 보고, 한글 숫자로 쓴 금액·비율은 값을 대조할 수 없으므로 거절한다.
 */
final class AnswerNumberGuard {
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:,\\d{3})*(?:\\.\\d+)?");
    /** 구백만원·삼십퍼센트처럼 한글 숫자 뒤에 단위가 붙은 표기. */
    private static final Pattern KOREAN_AMOUNT = Pattern.compile("[영일이삼사오육칠팔구십백천만억조]+\\s*(?:원|%|퍼센트|프로)");
    /** 근거의 칸: | 와 줄바꿈, 그리고 한 줄 안의 문장·절(마침표·쉼표 뒤 공백) 경계. 숫자 속 쉼표(1,000)는 나누지 않는다. */
    private static final Pattern SEGMENT_BREAK = Pattern.compile("[|\\n]|[.,]\\s");
    private static final Pattern CLAUSE_BREAK = Pattern.compile("[.!?\\n]");
    /** 같은 값을 가리키는 표현끼리 한 그룹이다. 그룹 안의 어느 말로 써도 같은 항목으로 본다. */
    private static final List<List<String>> LABEL_GROUPS = List.of(
            List.of("순수령액"),
            List.of("환수"),
            // 수령 예상(한도 반영 전)과 한도 반영(한도 적용 후)은 이월액만큼 다른 값이다. "한도 반영 수령 예상액"은 한도 반영 쪽이다.
            List.of("한도 반영 수령 예상", "한도 반영"),
            List.of("수령 예상"),
            List.of("이월"));

    private AnswerNumberGuard() {
    }

    static boolean allowed(String text, String source) {
        String answer = normalize(text);
        String facts = normalize(source);
        if (KOREAN_AMOUNT.matcher(answer).find()) {
            return false;
        }
        if (!numbers(facts).containsAll(numbers(answer))) {
            return false;
        }
        return labelledAmountsMatch(answer, facts);
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC);
    }

    private static Set<String> numbers(String text) {
        Set<String> found = new HashSet<>();
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            found.add(m.group().replace(",", ""));
        }
        return found;
    }

    /** 숫자 바로 앞 같은 문장에서 가장 가까운 항목 이름을 찾고, 근거의 같은 줄·칸에 그 항목과 숫자가 함께 있는지 본다. */
    private static boolean labelledAmountsMatch(String answer, String facts) {
        List<String> segments = List.of(SEGMENT_BREAK.split(facts));
        Matcher m = NUMBER.matcher(answer);
        while (m.find()) {
            int sentenceStart = sentenceStart(answer, m.start());
            List<String> group = nearestLabelGroup(answer.substring(sentenceStart, m.start()));
            if (group == null) {
                continue;
            }
            String number = m.group().replace(",", "");
            boolean bound = segments.stream().anyMatch(segment -> group.stream().anyMatch(segment::contains)
                    && numbers(segment).contains(number));
            if (!bound) {
                return false;
            }
        }
        return true;
    }

    private static int sentenceStart(String text, int end) {
        int start = 0;
        Matcher breaks = CLAUSE_BREAK.matcher(text.substring(0, end));
        while (breaks.find()) {
            start = breaks.end();
        }
        return start;
    }

    private static List<String> nearestLabelGroup(String beforeNumber) {
        List<String> best = null;
        int bestEnd = -1;
        int bestLength = 0;
        for (List<String> group : LABEL_GROUPS) {
            for (String label : group) {
                int at = beforeNumber.lastIndexOf(label);
                if (at < 0) {
                    continue;
                }
                int end = at + label.length();
                // 가장 뒤에서 끝나는 이름, 같은 위치면 더 긴 이름(한도 반영 수령 예상 > 수령 예상)을 고른다.
                if (end > bestEnd || (end == bestEnd && label.length() > bestLength)) {
                    bestEnd = end;
                    bestLength = label.length();
                    best = group;
                }
            }
        }
        return best;
    }
}
