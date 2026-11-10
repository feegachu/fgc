package com.susukkang.fgc.assistant.service;

import java.text.Normalizer;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Pattern redaction for user-authored prose; business records are never added to the prompt. */
public final class PicoTextSanitizer {
    private static final Pattern SECRET = Pattern.compile("(?i)(?:sk-[a-z0-9_-]{8,}|bearer\\s+[a-z0-9._-]+)");
    private static final Pattern EMAIL = Pattern.compile("(?<![\\w.+-])[\\w.+-]++@[\\w-]++(?:\\.[\\w-]++)*+");
    private static final Pattern NAMED_PERSON = Pattern.compile("(고객|수취인|설계사|성명|이름)\\s*+[:：]?\\s*+\\p{IsHangul}{2,6}");
    private static final String PERCENT = "\\s?(?:%|퍼센트|프로)";
    /**
     * A removed number takes its glued unit with it. Left behind, the model drops the marker and opens its answer with
     * "% 위반 예외는" or "년 분급 예정 스케줄은" (실측 2026-11-10). A long unspaced word loses only its leading unit.
     */
    private static final String UNIT = "(?:[\\p{L}%]{1,3}+(?![\\p{L}%])|" + PERCENT + "|개월|회차|[년월일원건])?+";

    private PicoTextSanitizer() {}

    /** approvedNumericTerms are business vocabulary such as "1,200%": they stay, in the approved spelling. Every other number is removed. */
    public static String redact(String text, Collection<String> approvedNumericTerms) {
        String result = Normalizer.normalize(text, Normalizer.Form.NFKC);
        result = SECRET.matcher(result).replaceAll("[인증정보 제거]");
        result = EMAIL.matcher(result).replaceAll("[이메일 제거]");
        result = NAMED_PERSON.matcher(result).replaceAll("$1 [이름 제거]");
        var terms = List.copyOf(approvedNumericTerms);
        String kept = terms.stream().map(term -> "(" + approvedTerm(term).pattern() + ")|").collect(Collectors.joining());
        // A number never runs on into an approved term: "2026-11 1,200% 위반" keeps the term.
        String stop = terms.isEmpty() ? "" : "(?!" + terms.stream().map(term -> approvedTerm(term).pattern()).collect(Collectors.joining("|")) + ")";
        Matcher numbers = Pattern.compile(kept + "[\\p{N}]++(?:[\\s.,/-]" + stop + "[\\p{N}]++)*+" + UNIT).matcher(result);
        var redacted = new StringBuilder();
        while (numbers.find()) {
            String replacement = "[수치 제거]";
            for (int index = 0; index < terms.size(); index++) if (numbers.group(index + 1) != null) replacement = terms.get(index);
            numbers.appendReplacement(redacted, Matcher.quoteReplacement(replacement));
        }
        return numbers.appendTail(redacted).toString();
    }

    /** "1,200%" also covers 1200%, 1,200 % and 1,200퍼센트, but not 11,200%, 1,200.5% or 1,200원. */
    public static Pattern approvedTerm(String term) {
        String digits = term.replaceAll("\\D", "");
        var number = new StringBuilder();
        for (int index = 0; index < digits.length(); index++) {
            if (index > 0 && (digits.length() - index) % 3 == 0) number.append(",?");
            number.append(digits.charAt(index));
        }
        return Pattern.compile("(?<![\\p{N}.,])" + number + PERCENT + "(?!\\p{N})");
    }
}
