package com.susukkang.fgc.assistant;

import com.susukkang.fgc.assistant.service.PicoTextSanitizer;
import org.junit.jupiter.api.Test;
import java.text.Normalizer;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class PicoTextSanitizerTest {
    @Test void redactsCompleteEmailAndCanonicallyEquivalentHangulNames() {
        String input = Normalizer.normalize("고객 김철수 ops+demo@sample.co.kr 123,456.78원", Normalizer.Form.NFD);
        assertThat(PicoTextSanitizer.redact(input))
            .contains("고객 [이름 제거]", "[이메일 제거]", "[수치 제거]원")
            .doesNotContain("김철수", "ops+demo", "sample.co.kr", "123", "456");
    }
    @Test void longRunsAndSeparatedNumbersDoNotBacktrackOrOverflowTheStack() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertThat(PicoTextSanitizer.redact("a".repeat(50000) + "@example.com")).isEqualTo("[이메일 제거]");
            String plain = "a".repeat(50000) + "!";
            assertThat(PicoTextSanitizer.redact(plain)).isEqualTo(plain);
            assertThat(PicoTextSanitizer.redact("7.".repeat(5000) + "7")).isEqualTo("[수치 제거]");
        });
    }
}
