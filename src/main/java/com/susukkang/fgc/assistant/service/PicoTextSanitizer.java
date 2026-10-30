package com.susukkang.fgc.assistant.service;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** Pattern redaction for user-authored prose; business records are never added to the prompt. */
public final class PicoTextSanitizer {
    private static final Pattern SECRET = Pattern.compile("(?i)(?:sk-[a-z0-9_-]{8,}|bearer\\s+[a-z0-9._-]+)");
    private static final Pattern EMAIL = Pattern.compile("(?<![\\w.+-])[\\w.+-]++@[\\w-]++(?:\\.[\\w-]++)*+");
    private static final Pattern NAMED_PERSON = Pattern.compile("(고객|수취인|설계사|성명|이름)\\s*[:：]?\\s*\\p{IsHangul}{2,6}");
    private static final Pattern NUMERIC = Pattern.compile("[\\p{N}]++(?:[\\s.,/-][\\p{N}]++)*+");

    private PicoTextSanitizer() {}

    public static String redact(String text) {
        String result = Normalizer.normalize(text, Normalizer.Form.NFKC);
        result = SECRET.matcher(result).replaceAll("[인증정보 제거]");
        result = EMAIL.matcher(result).replaceAll("[이메일 제거]");
        result = NAMED_PERSON.matcher(result).replaceAll("$1 [이름 제거]");
        return NUMERIC.matcher(result).replaceAll("[수치 제거]");
    }
}
