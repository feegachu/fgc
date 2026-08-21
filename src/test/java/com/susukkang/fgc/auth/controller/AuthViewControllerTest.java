package com.susukkang.fgc.auth.controller;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.ui.ExtendedModelMap;

import static org.assertj.core.api.Assertions.assertThat;

class AuthViewControllerTest {

    @Test
    void localProfileShowsQaRequestLab() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        ExtendedModelMap model = new ExtendedModelMap();

        String view = new AuthViewController(environment).loginPage(model);

        assertThat(view).isEqualTo("auth/login");
        assertThat(model.get("qaRequestLabEnabled")).isEqualTo(true);
    }

    @Test
    void productionProfileHidesQaRequestLab() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        ExtendedModelMap model = new ExtendedModelMap();

        String view = new AuthViewController(environment).loginPage(model);

        assertThat(view).isEqualTo("auth/login");
        assertThat(model.get("qaRequestLabEnabled")).isEqualTo(false);
    }
}
