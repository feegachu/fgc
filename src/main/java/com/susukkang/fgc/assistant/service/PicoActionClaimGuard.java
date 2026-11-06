package com.susukkang.fgc.assistant.service;

import java.util.List;

/** AI explanations cannot attest that a business action happened (FUN-073).
 * Match affirmative completion endings, keeping conditions and instructions.
 * The caller supplies NFKC-normalized text; no model text is logged here.
 */
final class PicoActionClaimGuard {
    private static final String COMPLETE = "완료";
    private static final String PROCESS = "처리";
    private static final String CONFIRM = "확정";
    private static final List<String> ACTIONS = List.of("저장", CONFIRM, "지급", PROCESS, COMPLETE,
            "등록", "수정", "정정", "반영", "승인", "통과", "실행", "제출", "취소",
            "검증", "대사", "계산", "재시작", "생성", "시행", "적용", "해제", "보류", "재개", "발행");
    private static final List<String> MODIFIERS = List.of("제가", "대신", "직접", "미리", "방금",
            "모두", "전부", "이미", "정상적으로", "성공적으로");
    private static final List<String> PAST = List.of("했", "하였", "됐", "되었", "마쳤", "끝냈", "끝났");
    private static final List<String> AUXILIARY = List.of("드렸", "두었", "뒀", "놓았");
    private static final List<String> ENDINGS = List.of("습니다", "어요", "어", "다", "죠", "네요", "네",
            "고", "으니", "으니까", "으므로", "는데", "지만");
    private static final List<String> STATE_ENDINGS = List.of("입니다", "예요", "이에요", "에요");
    private static final List<String> PRESENTATION = List.of("설명", "설명을", "안내", "안내를", "답변", "답변을");

    private PicoActionClaimGuard() { }

    static boolean matches(String normalizedText) {
        return ACTIONS.stream().anyMatch(action -> claimsAction(normalizedText, action));
    }

    private static boolean claimsAction(String text, String action) {
        for (int position = text.indexOf(action); position >= 0;
                position = text.indexOf(action, position + action.length())) {
            String prefix = text.substring(0, position).stripTrailing();
            if (action.equals(COMPLETE) && PRESENTATION.stream().anyMatch(prefix::endsWith)) continue;
            String tail = afterModifiers(text.substring(position + action.length()).stripLeading());
            String predicate = withoutCompletionWords(tail);
            if (pastAssertion(predicate) || stateAssertion(predicate, action)
                    || nominalAssertion(tail, action)) return true;
        }
        return false;
    }

    private static String afterModifiers(String tail) {
        // Do not consume the first syllable of 이미 as a subject particle.
        if (!tail.isEmpty() && "을를이가은는".indexOf(tail.charAt(0)) >= 0
                && !tail.startsWith("이미") && !copula(tail)) tail = tail.substring(1).stripLeading();
        for (int count = 0; count < 3; count++) {
            String remaining = tail;
            var modifier = MODIFIERS.stream().filter(remaining::startsWith).findFirst();
            if (modifier.isEmpty()) break;
            tail = tail.substring(modifier.get().length()).stripLeading();
        }
        return tail;
    }

    private static boolean copula(String text) {
        return STATE_ENDINGS.stream().anyMatch(text::startsWith);
    }

    private static String withoutCompletionWords(String tail) {
        for (int count = 0; count < 2 && (tail.startsWith(COMPLETE) || tail.startsWith(PROCESS)); count++)
            tail = tail.substring(2).stripLeading();
        return tail;
    }

    private static boolean nominalAssertion(String tail, String action) {
        String afterProcess = tail.startsWith(PROCESS) ? tail.substring(PROCESS.length()).stripLeading() : tail;
        if (!action.equals(COMPLETE) && !afterProcess.startsWith(COMPLETE)) return false;
        String predicate = withoutCompletionWords(tail);
        return hasEnding(predicate, "", STATE_ENDINGS) || predicate.isEmpty()
                || ".!?。…".indexOf(predicate.charAt(0)) >= 0;
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
        else if (!action.equals(CONFIRM) && !action.equals(COMPLETE)) return false;
        return hasEnding(tail, "상태", STATE_ENDINGS);
    }

    private static boolean hasEnding(String text, String stem, List<String> endings) {
        if (!text.startsWith(stem)) return false;
        return endings.stream().anyMatch(ending -> text.startsWith(ending, stem.length())
                && boundary(text, stem.length() + ending.length()));
    }

    private static boolean boundary(String text, int index) {
        // Punctuation, symbols and formatting delimit an assertion; Korean
        // conditional suffixes (했다면/했는지/했을 때) remain letters, not boundaries.
        return index == text.length() || !Character.isLetterOrDigit(text.codePointAt(index));
    }
}
