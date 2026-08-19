package com.susukkang.fgc.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AppShellWorkspaceStructureTest {

    @Test
    void headerRailContainsWorkspaceTabsAndFixedControlsWithoutLegacyTitleArea() throws IOException {
        String layout = resource("templates/layout/default.html");
        String header = resource("templates/layout/fragments/header.html");

        assertThat(layout)
                .contains("class=\"app-shell-layout\"")
                .contains("header :: header(${screenId}, ${title})")
                .contains("/js/common/sidebar-state-init.js")
                .doesNotContain("app-sidebar-backdrop");

        assertThat(header)
                .contains("class=\"app-header app-header-rail\"")
                .contains("workspaceTabs(${activeTabId}, ${activeTabTitle})")
                .contains("class=\"app-header-actions\"")
                .doesNotContain("app-header-role")
                .doesNotContain("app-header-leading")
                .doesNotContain("app-header-breadcrumb");
    }

    @Test
    void shellAndTabsUseProductionDimensionsAndFullWidthAccent() throws IOException {
        assertThat(resource("static/css/common/variables.css"))
                .contains("--layout-sidebar-width: 16rem")
                .contains("--layout-sidebar-collapsed-width: 4.5rem")
                .contains("--layout-header-height: 3.25rem")
                .contains("--layout-workspace-tabs-height: 2.5rem")
                .contains("--layout-workspace-tab-width: 13.75rem");

        assertThat(resource("static/css/common/layout.css"))
                .contains("grid-template-columns: var(--layout-sidebar-current-width) minmax(0, 1fr)")
                .contains(".app-shell.is-sidebar-collapsed")
                .contains("flex: 0 0 var(--layout-workspace-tab-width)")
                .contains(".workspace-tab.is-modified:not(:hover):not(:focus-within)")
                .contains("background: var(--color-bg-surface)")
                .contains(".workspace-tab.is-active::before")
                .contains("top: 0;\n  right: 0;\n  left: 0;");
    }

    @Test
    void workspaceTabsPersistStateAndChooseRightThenLeftThenDashboardOnClose() throws IOException {
        String script = resource("static/js/common/workspace-tabs.js");

        assertThat(script)
                .contains("fgc.workspace-tabs.v1")
                .contains("window.sessionStorage.getItem")
                .contains("window.sessionStorage.setItem")
                .contains("tab.modified === true")
                .contains("workspace-tab-modified")
                .contains("markModified")
                .contains("tabs[Math.min(index, tabs.length - 1)]")
                .contains("id: DASHBOARD_TAB_ID")
                .contains("ResizeObserver");
    }

    private static String resource(String path) throws IOException {
        try (var input = AppShellWorkspaceStructureTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
