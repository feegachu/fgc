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

    private static String resource(String path) throws IOException {
        try (var input = SidebarSectionStateStructureTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
