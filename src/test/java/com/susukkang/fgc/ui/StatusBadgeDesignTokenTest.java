package com.susukkang.fgc.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class StatusBadgeDesignTokenTest {

    @Test
    void warningAndRiskSolidBadgesUseProductionColorsWithoutChangingSoftTokens() throws IOException {
        String variables = resource("static/css/common/variables.css");
        String components = resource("static/css/common/components.css");

        assertThat(variables)
                .contains("--color-status-warning-solid: #ea993e;")
                .contains("--color-status-risk-solid: #ec7825;")
                .contains("--color-status-warning-bg: #fffbeb;")
                .contains("--color-status-warning-border: #fcd34d;")
                .contains("--color-status-risk-bg: #fff7ed;")
                .contains("--color-status-risk-border: #fdba74;")
                .doesNotContain("--color-status-warning-solid: #b85c15;")
                .doesNotContain("--color-status-risk-solid: #c95117;");

        assertThat(components)
                .contains("color: var(--color-text-inverse);")
                .contains(".status-badge-warning {\n  background: var(--color-status-warning-solid);\n}")
                .contains(".status-badge-risk {\n  background: var(--color-status-risk-solid);\n}");
    }

    private static String resource(String path) throws IOException {
        try (var input = StatusBadgeDesignTokenTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
