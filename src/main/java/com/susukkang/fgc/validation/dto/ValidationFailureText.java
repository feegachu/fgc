package com.susukkang.fgc.validation.dto;

import java.util.regex.Pattern;

/**
 * API로 내보내는 월 통합검증 실패 사유 (화면정의서 공통규칙 10, SRC-074).
 * 옛 실행의 failure_message에는 SQL 같은 예외 원문이 남아 있어 응답에서 뺀다. 저장값은 그대로 둔다.
 * 판별 기준은 화면의 failureText, isDeveloperText와 같다.
 */
public final class ValidationFailureText {
    private static final Pattern REQUEST_NUMBER = Pattern.compile("요청번호 [\\w.-]+", Pattern.CANON_EQ);
    private static final Pattern DEVELOPER_TEXT = Pattern.compile("[A-Za-z_]{3,}");

    private ValidationFailureText() {
    }

    /**
     * 사유 전체가 사용자 문장일 때만 내보낸다. 없으면 null이고, 화면은 실패한 단계로 안내한다.
     * 조각만 골라 살리지 않는다. SQL의 || 도 구분자로 잘려 원문 조각이 남기 때문이다.
     */
    public static String forResponse(String stored) {
        return stored == null || stored.isBlank() || isDeveloperText(stored) ? null : stored;
    }

    private static boolean isDeveloperText(String text) {
        return DEVELOPER_TEXT.matcher(REQUEST_NUMBER.matcher(text).replaceAll("")).find();
    }
}
