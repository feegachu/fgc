package com.susukkang.fgc.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AuditTemplateStructureTest {

    @Test
    void auditListUsesSharedDisclosureAndAccessibleRowSelection() throws IOException {
        assertThat(resource("templates/audit/list.html"))
                .doesNotContain("class=\"audit-notice\"")
                .doesNotContain("class=\"page-description\"")
                .doesNotContain("바뀐 칸만 노랗게 표시")
                .contains("tabindex=\"0\"")
                .contains("aria-controls=\"diff-body\"")
                .contains("th:data-audit-detail-href")
                .contains("class=\"table-cell-disclosure\"")
                .contains("class=\"table-cell-details\" hidden")
                .contains("전체 보기")
                .contains("접기");

        assertThat(resource("static/css/features/audit.css"))
                .doesNotContain(".audit-notice")
                .contains(".audit-log-row:focus-visible")
                .contains(".data-table td.audit-disclosure-cell");

        assertThat(resource("static/css/common/components.css"))
                .contains(".table-cell-disclosure")
                .contains(".table-cell-preview.is-single-line")
                .contains(".table-cell-details summary:focus-visible")
                .contains(".table-cell-details[open] .table-cell-less");

        assertThat(resource("templates/layout/default.html"))
                .contains("/js/features/audit/audit-list.js");

        assertThat(resource("static/js/features/audit/audit-list.js"))
                .contains("[data-audit-detail-href]")
                .contains("row.addEventListener(\"click\"")
                .contains("row.addEventListener(\"keydown\"")
                .contains("event.key !== \"Enter\"")
                .contains("event.target !== row")
                .contains("a, button, input, select, textarea, summary, details")
                .contains("window.location.assign(href)")
                .contains("preview.scrollWidth > preview.clientWidth")
                .contains("preview.scrollHeight > preview.clientHeight")
                .contains("document.fonts.ready");
    }

    private String resource(String path) throws IOException {
        try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Resource not found: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
