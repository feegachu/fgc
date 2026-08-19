package com.susukkang.fgc.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SidebarSectionStateStructureTest {

    @Test
    void sidebarSectionsExposeStablePersistenceKeys() throws IOException {
        String sidebar = resource("templates/layout/fragments/sidebar.html");

        assertThat(sidebar)
                .contains("data-sidebar-section=\"contracts\"")
                .contains("data-sidebar-section=\"compliance\"")
                .contains("data-sidebar-section=\"ledger\"")
                .contains("data-sidebar-section=\"admin\"");
    }

    @Test
    void appShellContainsSidebarPersistenceHooks() throws IOException {
        String appShell = resource("static/js/common/app-shell.js");

        assertThat(appShell)
                .contains("fgc.sidebar.open-sections.v1")
                .contains("window.sessionStorage.getItem")
                .contains("window.sessionStorage.setItem")
                .contains("section.addEventListener(\"toggle\"")
                .contains("if (savedOpenSections.indexOf(sectionId) !== -1) section.open = true");
    }

    @Test
    void sidebarCollapseStateUsesExplicitAccessibleControlsAndLocalStorage() throws IOException {
        String sidebar = resource("templates/layout/fragments/sidebar.html");
        String header = resource("templates/layout/fragments/header.html");
        String appShell = resource("static/js/common/app-shell.js");
        String initialState = resource("static/js/common/sidebar-state-init.js");

        assertThat(sidebar)
                .contains("aria-label=\"사이드바 접기\"")
                .contains("aria-label=\"사이드바 펼치기\"")
                .contains("keyboard_double_arrow_left")
                .contains("width=\"48\" height=\"48\"");

        assertThat(header)
                .doesNotContain("data-action=\"open-app-sidebar\"")
                .doesNotContain("app-header-title");

        assertThat(appShell)
                .contains("fgc.sidebar.collapsed.v1")
                .contains("window.localStorage.getItem")
                .contains("window.localStorage.setItem")
                .contains("narrowSidebarMedia.matches")
                .contains("is-sidebar-collapsed")
                .contains("sidebar.contains(event.target)")
                .contains("sectionSummary.closest(\"details\")")
                .contains("if (section) section.open = true")
                .doesNotContain("event.target.closest(\"a, button, summary");

        assertThat(initialState)
                .contains("fgc.sidebar.collapsed.v1")
                .contains("window.localStorage.getItem")
                .contains("document.body.classList.toggle(\"is-sidebar-collapsed\"")
                .contains("document.body.classList.add(\"is-sidebar-ready\"") ;

        assertThat(resource("static/js/features/auth/login.js"))
                .contains("[sessionStorage, localStorage]")
                .contains("key.startsWith(\"fgc.\")");
    }

    private static String resource(String path) throws IOException {
        try (var input = SidebarSectionStateStructureTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
