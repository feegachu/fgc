package com.susukkang.fgc.common.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRequestAuthorizationTest {
    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "GA_ADMIN", "SETTLEMENT", "COMPLIANCE"})
    void existingRolesNeverMatchAgentRule(String role) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "existing", null, AuthorityUtils.createAuthorityList("ROLE_" + role)));
        assertThat(AgentRequestAuthorization.IS_AGENT.matches(new MockHttpServletRequest())).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/contracts", "/api/v1/contracts/1", "/api/v1/contracts/1/status-events",
            "/api/v1/contracts/export.csv", "/api/v1/transactions", "/api/v1/transactions/1",
            "/api/v1/auth/me", "/api/v1/base/products", "/api/v1/news/headlines"})
    void permitsOnlyScopedReads(String path) {
        assertThat(AgentRequestAuthorization.SCOPED_READS.check(() -> null,
                new RequestAuthorizationContext(new MockHttpServletRequest("GET", path))).isGranted()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/contracts/1/journals", "/api/v1/contracts/1/transactions",
            "/api/v1/contracts/1/schedules", "/api/v1/intake/documents", "/api/v1/intake/documents/1/source",
            "/api/v1/dashboard", "/api/v1/base/agents", "/api/v1/documents/other.pdf", "/contracts/1",
            "/api/v1/contracts/1/status-events/extra", "/api/v1/transactions/1/confirm"})
    void internalOrUnclassifiedReadsStayClosed(String path) {
        assertThat(AgentRequestAuthorization.SCOPED_READS.check(() -> null,
                new RequestAuthorizationContext(new MockHttpServletRequest("GET", path))).isGranted()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/ai/compare-chat/rank", "/api/v1/ai/compare-chat/explain", "/api/v1/ai/compare-chat/ask"})
    void opensOnlyCompareChatPosts(String path) {
        assertThat(AgentRequestAuthorization.SCOPED_READS.check(() -> null,
                new RequestAuthorizationContext(new MockHttpServletRequest("POST", path))).isGranted()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH"})
    void neverOpensInternalWrites(String method) {
        assertThat(AgentRequestAuthorization.SCOPED_READS.check(() -> null,
                new RequestAuthorizationContext(new MockHttpServletRequest(method, "/api/v1/contracts")))
                .isGranted()).isFalse();
    }
}
