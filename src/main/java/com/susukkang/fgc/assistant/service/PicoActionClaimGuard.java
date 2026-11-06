package com.susukkang.fgc.assistant.service;

import java.util.List;
import java.util.regex.Pattern;

/** AI explanations cannot attest that a business action happened (FUN-073).
 * Match affirmative completion endings, keeping conditions and instructions.
 * The caller supplies NFKC-normalized text; no model text is logged here.
 */
final class PicoActionClaimGuard {
    private static final Pattern ACTION = Pattern.compile("저장|확정|지급|처리|완료");
    private static final List<String> MODIFIERS = List.of("제가", "대신", "직접", "미리", "방금",
            "모두", "전부", "이미", "정상적으로", "성공적으로");
    private static final List<String> PAST = List.of("했", "하였", "됐", "되었", "마쳤", "끝냈", "끝났");
    private static final List<String> AUXILIARY = List.of("드렸", "두었", "뒀", "놓았");
    private static final List<String> ENDINGS = List.of("습니다", "어요", "어", "다", "죠", "네요", "네");
    private static final List<String> STATE_ENDINGS = List.of("입니다", "예요", "이에요", "에요");

    private PicoActionClaimGuard() { }

    static boolean matches(String normalizedText) {
        var actions = ACTION.matcher(normalizedText);
        while (actions.find()) {
            String tail = afterModifiers(normalizedText.substring(actions.end()).stripLeading());
            if (pastAssertion(tail) || stateAssertion(tail, actions.group())) return true;
        }
        return false;
    }

    private static String afterModifiers(String tail) {
        // Do not consume the first syllable of 이미 as a subject particle.
        if (!tail.isEmpty() && "을를이가은는".indexOf(tail.charAt(0)) >= 0
                && !tail.startsWith("이미")) tail = tail.substring(1).stripLeading();
        for (int count = 0; count < 3; count++) {
            String remaining = tail;
            var modifier = MODIFIERS.stream().filter(remaining::startsWith).findFirst();
            if (modifier.isEmpty()) break;
            tail = tail.substring(modifier.get().length()).stripLeading();
        }
        if (tail.startsWith("완료") || tail.startsWith("처리")) tail = tail.substring(2).stripLeading();
        return tail;
    }

    private static boolean pastAssertion(String tail) {
        if (PAST.stream().anyMatch(stem -> hasEnding(tail, stem, ENDINGS))) return true;
        if (!tail.startsWith("해")) return false;
        String auxiliary = tail.substring(1).stripLeading();
        return AUXILIARY.stream().anyMatch(stem -> hasEnding(auxiliary, stem, ENDINGS));
    }

    private static boolean stateAssertion(String tail, String action) {
        if (tail.startsWith("된") || tail.startsWith("한")) tail = tail.substring(1).stripLeading();
        // A field named 지급 상태/처리 상태 is not evidence of a completed action.
        else if (!action.equals("확정") && !action.equals("완료")) return false;
        return hasEnding(tail, "상태", STATE_ENDINGS);
    }

    private static boolean hasEnding(String text, String stem, List<String> endings) {
        if (!text.startsWith(stem)) return false;
        return endings.stream().anyMatch(ending -> text.startsWith(ending, stem.length())
                && boundary(text, stem.length() + ending.length()));
    }

    private static boolean boundary(String text, int index) {
        return index == text.length() || Character.isWhitespace(text.charAt(index))
                || ".!?。…'‘’\"“”".indexOf(text.charAt(index)) >= 0;
    }
}
