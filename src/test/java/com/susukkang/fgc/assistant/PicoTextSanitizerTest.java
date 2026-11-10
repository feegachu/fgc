package com.susukkang.fgc.assistant;

import com.susukkang.fgc.assistant.service.PicoTextSanitizer;
import org.junit.jupiter.api.Test;
import java.text.Normalizer;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class PicoTextSanitizerTest {
    private static final List<String> APPROVED = List.of("1,200%");

    @Test void redactsCompleteEmailAndCanonicallyEquivalentHangulNames() {
        String input = Normalizer.normalize("고객  :  김철수 ops+demo@sample.co.kr 123,456.78원", Normalizer.Form.NFD);
        assertThat(PicoTextSanitizer.redact(input, APPROVED))
            .contains("고객 [이름 제거]", "[이메일 제거]", "[수치 제거]")
            .doesNotContain("김철수", "ops+demo", "sample.co.kr", "123", "456", "원");
    }
    @Test void longRunsAndSeparatedNumbersDoNotBacktrackOrOverflowTheStack() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertThat(PicoTextSanitizer.redact("a".repeat(50000) + "@example.com", APPROVED)).isEqualTo("[이메일 제거]");
            String missingName = "고객" + " ".repeat(50000) + "!";
            assertThat(PicoTextSanitizer.redact(missingName, APPROVED)).isEqualTo(missingName);
            String plain = "a".repeat(50000) + "!";
            assertThat(PicoTextSanitizer.redact(plain, APPROVED)).isEqualTo(plain);
            assertThat(PicoTextSanitizer.redact("7.".repeat(5000) + "7", APPROVED)).isEqualTo("[수치 제거]");
            assertThat(PicoTextSanitizer.redact("1,200% ".repeat(5000) + "가".repeat(50000), APPROVED)).startsWith("1,200% 1,200% ");
            assertThat(PicoTextSanitizer.redact("1,200 ".repeat(5000) + "%", APPROVED)).as("숫자가 길게 이어져도 끝의 승인 용어만 남는다").isEqualTo("[수치 제거] 1,200%");
            assertThat(PicoTextSanitizer.redact("7" + "가".repeat(50000), APPROVED)).startsWith("[수치 제거]가가");
        });
    }
    /** 2026-11-10 실측: 숫자를 지우면 '1,200% 한도 검증 화면은 어디에 있나요?'가 3회 모두 범위 밖으로 거절됐다. 승인된 화면 이름의 숫자는 업무 용어다. */
    @Test void approvedNumericTermIsKeptInItsApprovedSpelling() {
        assertThat(PicoTextSanitizer.redact("1,200% 위반 예외는 어떻게 처리하나요?", APPROVED)).isEqualTo("1,200% 위반 예외는 어떻게 처리하나요?");
        assertThat(PicoTextSanitizer.redact("1,200%룰이 뭐예요?", APPROVED)).isEqualTo("1,200%룰이 뭐예요?");
        assertThat(PicoTextSanitizer.redact("2026-11 1,200% 위반 예외", APPROVED)).as("앞의 다른 숫자가 승인 용어까지 삼키지 않는다").isEqualTo("[수치 제거] 1,200% 위반 예외");
        for (String typed : List.of("1200% 위반", "1,200 % 위반", "１，２００％ 위반", "1,200퍼센트 위반", "1200 프로 위반"))
            assertThat(PicoTextSanitizer.redact(typed, APPROVED)).as(typed).isEqualTo("1,200% 위반");
    }
    @Test void numbersThatOnlyLookLikeTheApprovedTermAreStillRemoved() {
        for (String typed : List.of("한도가 1,500%인가요?", "1,200원이 맞나요?", "11,200% 인가요?", "1,200.5% 인가요?", "1,200건 처리", "0.1,200%"))
            assertThat(PicoTextSanitizer.redact(typed, APPROVED)).as(typed).contains("[수치 제거]").doesNotContainPattern("\\d");
        assertThat(PicoTextSanitizer.redact("1,200% 한도인데 사용률 1,350%는 뭔가요?", APPROVED)).isEqualTo("1,200% 한도인데 사용률 [수치 제거] 뭔가요?");
        assertThat(PicoTextSanitizer.redact("1,200% 위반", List.of())).as("승인 목록이 비면 모두 가린다").isEqualTo("[수치 제거] 위반");
    }
    /** 같은 실측: 표식 뒤에 단위가 남으면 모델이 표식만 빼고 '% 위반 예외는', '년 분급 예정 스케줄은'으로 답을 시작했다. */
    @Test void removedNumberTakesItsShortAttachedUnitSoNoUnitIsLeftDangling() {
        assertThat(PicoTextSanitizer.redact("4년 분급 예정 스케줄은 어디서 확인하나요?", APPROVED)).isEqualTo("[수치 제거] 분급 예정 스케줄은 어디서 확인하나요?");
        assertThat(PicoTextSanitizer.redact("12회차 지급을 3건 등록하려면?", APPROVED)).isEqualTo("[수치 제거] 지급을 [수치 제거] 등록하려면?");
        assertThat(PicoTextSanitizer.redact("이 지급 건은 1,000,000원이 맞죠?", APPROVED)).isEqualTo("이 지급 건은 [수치 제거] 맞죠?");
        assertThat(PicoTextSanitizer.redact("11월 월 통합검증은 어떻게 확정하나요?", APPROVED)).isEqualTo("[수치 제거] 월 통합검증은 어떻게 확정하나요?");
        assertThat(PicoTextSanitizer.redact("사용률 74.5 %가 뭐예요?", APPROVED)).isEqualTo("사용률 [수치 제거]가 뭐예요?");
        // 띄어 쓰지 않은 긴 낱말은 통째로 지우지 않는다. 앞의 단위만 가져가고 나머지 뜻은 남긴다.
        assertThat(PicoTextSanitizer.redact("4년분급스케줄어디서봐요", APPROVED)).isEqualTo("[수치 제거]분급스케줄어디서봐요");
        assertThat(PicoTextSanitizer.redact("지급 등록 3단계에서 뭘 입력해요?", APPROVED)).isEqualTo("지급 등록 [수치 제거]단계에서 뭘 입력해요?");
        assertThat(PicoTextSanitizer.redact("설계사 agent02 수수료", APPROVED)).isEqualTo("설계사 agent[수치 제거] 수수료");
    }
}
