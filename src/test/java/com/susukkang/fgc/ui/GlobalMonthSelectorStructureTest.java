package com.susukkang.fgc.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalMonthSelectorStructureTest {

    @Test
    void headerProvidesAccessiblePopoverAndTwelveMonthCells() throws IOException {
        String header = resource("templates/layout/fragments/header.html");

        assertThat(header)
                .contains("data-month-selector")
                .contains("aria-haspopup=\"dialog\"")
                .contains("aria-expanded=\"false\"")
                .contains("role=\"dialog\"")
                .contains("role=\"grid\"")
                .contains("tabindex=\"-1\"")
                .contains("data-month-index=${monthIndex}")
                .contains("${#numbers.sequence(0, 2)}")
                .contains("${#numbers.sequence(1, 4)}")
                .contains("data-action=\"cancel-month-selector\"")
                .contains("data-action=\"apply-month-selector\"");
    }

    @Test
    void componentKeepsDraftSeparateUntilApplyAndSupportsDismissal() throws IOException {
        assertThat(resource("static/js/common/month-selector.js"))
                .contains("displayYear: Number(value.slice(0, 4))")
                .contains("nextYear < 1 || nextYear > 9999")
                .contains("previousYearButton")
                .contains("nextYearButton")
                .contains("cancelButton")
                .contains("state.draftValue = state.value")
                .contains("state.draftValue = cell.dataset.month")
                .contains("state.draftValue = null")
                .contains("settings.onApply(nextValue, state.value)")
                .contains("event.key === \"Escape\"")
                .contains("event.key === \"Enter\"")
                .contains("event.key === \" \"")
                .contains("!root.contains(event.target)")
                .contains("trigger.focus()")
                .contains("ArrowLeft: -1")
                .contains("ArrowUp: -4")
                .contains("cell.tabIndex = selected && !disabled ? 0 : -1")
                .contains("cell.disabled = disabled");
    }

    @Test
    void applySynchronizesEveryWorkspaceTabAndOnlyResetsPagination() throws IOException {
        assertThat(resource("static/js/common/workspace-tabs.js"))
                .contains("function applyGlobalMonth(month)")
                .contains("previousTabs.map")
                .contains("url.searchParams.set(\"month\", month)")
                .contains("url.searchParams.delete(\"page\")")
                .contains("getOpenTabCount")
                .contains("restoreTabs")
                .contains("tab.id === tabId");

        assertThat(resource("static/js/common/app-shell.js"))
                .contains("workspaceTabs.applyGlobalMonth(month)")
                .contains("navigateToGlobalMonth(month)")
                .contains("resolve();")
                .contains("workspaceTabs.restoreTabs(previousTabs)");
    }

    @Test
    void stylesMatchFigmaDimensionsAndProvideNarrowScreenFallback() throws IOException {
        assertThat(resource("static/css/common/layout.css"))
                .contains(".month-selector-popover")
                .contains("width: 22.5rem")
                .contains("top: calc(100% + var(--space-2))")
                .contains("grid-template-columns: repeat(4, minmax(0, 1fr))")
                .contains(".month-selector-cell.is-selected")
                .contains(".month-selector-cell.is-current")
                .contains("prefers-reduced-motion: reduce")
                .contains("position: fixed");
    }

    private static String resource(String path) throws IOException {
        try (var input = GlobalMonthSelectorStructureTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
