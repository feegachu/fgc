package com.susukkang.fgc.validation.dto;

import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * API로 내보내는 월 통합검증 실패 사유 (화면정의서 공통규칙 10, SRC-074).
 * 옛 실행의 failure_message에는 SQL 같은 예외 원문이 남아 있어 응답에서 뺀다. 저장값은 그대로 둔다.
 * 판별 기준은 화면의 failureText, isDeveloperText와 같다.
 */
public final class ValidationFailureText {
    private static final Pattern REQUEST_NUMBER = Pattern.compile("요청번호 [\\w.-]+");
    private static final Pattern CONTRACT_NUMBER = Pattern.compile("\\bFGC-[A-Z0-9]+-\\d{6}-\\d+\\b");
    private static final Pattern DEVELOPER_TEXT = Pattern.compile("[A-Za-z_]{3,}");

    private ValidationFailureText() {
    }

    /** 사용자 문장만 남긴다. 남는 문장이 없으면 null이고, 화면은 실패한 단계로 안내한다. */
    public static String forResponse(String stored) {
        if (stored == null) {
            return null;
        }
        String sentences = Arrays.stream(stored.split("\\|"))
                .map(String::strip)
                .filter(part -> !part.isEmpty() && !isDeveloperText(part))
                .collect(Collectors.joining(" | "));
        return sentences.isEmpty() ? null : sentences;
    }

    private static boolean isDeveloperText(String part) {
        String withoutNumbers = CONTRACT_NUMBER.matcher(REQUEST_NUMBER.matcher(part).replaceAll("")).replaceAll("");
        return DEVELOPER_TEXT.matcher(withoutNumbers).find();
    }
}
