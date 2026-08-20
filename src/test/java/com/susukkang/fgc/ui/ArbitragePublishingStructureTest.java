package com.susukkang.fgc.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ArbitragePublishingStructureTest {

    @Test
    void arbitrageListUsesCommonComponentsWithoutInlinePresentation() throws IOException {
        assertThat(resource("templates/arbitrage/list.html"))
                .contains("class=\"page-header\"")
                .contains("class=\"filter-bar arb-filter-bar\"")
                .contains("class=\"kpi-grid arb-summary-grid\"")
                .contains("class=\"surface arb-list-panel\"")
                .contains("class=\"data-table arb-list-table\"")
                .contains("id=\"arb-pagination\"")
                .contains("data-modal=\"arb-recheck\"")
                .doesNotContain("fgc-page-desc")
                .doesNotContain("fgc-banner")
                .doesNotContain("class=\"guidance")
                .doesNotContain("style=")
                .doesNotContain("<script>");

        assertThat(resource("templates/layout/default.html"))
                .contains("/css/features/arbitrage.css");
        assertThat(resource("static/css/features/arbitrage.css"))
                .contains(".arb-page")
                .contains(".arb-result-row.is-selected")
                .contains("@media (max-width: 71.875rem)");
    }

    @Test
    void arbitrageRowsKeepPlainContractTextAndKeyboardSelection() throws IOException {
        assertThat(resource("static/js/features/arbitrage/arbitrage-list.js"))
                .contains("document.querySelector(\".arb-page\")")
                .contains("status-badge status-badge-success")
                .contains("class=\"arb-result-row\"")
                .contains("tabindex=\"0\" aria-selected=\"false\"")
                .contains("event.key !== \"Enter\" && event.key !== \" \"")
                .doesNotContain("arb-row-select")
                .doesNotContain("fgc-btn")
                .doesNotContain("style=")
                .doesNotContain("fetch(");
    }

    private static String resource(String path) throws IOException {
        try (var input = ArbitragePublishingStructureTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
