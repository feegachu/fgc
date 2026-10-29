package com.susukkang.fgc.common.security;

import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.Set;

/** FUN-085 전용 규칙. 기존 4개 역할·미인증 요청은 이 matcher에 걸리지 않는다. */
public final class AgentRequestAuthorization {
    public static final RequestMatcher IS_AGENT = request -> {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(role -> role.getAuthority().equals("ROLE_" + Roles.AGENT));
    };

    private static final Set<String> PUBLIC_READS = Set.of("/api/v1/auth/me",
            "/api/v1/base/insurers", "/api/v1/base/products", "/api/v1/base/commission-items",
            "/api/v1/ai/compare-chat/categories");

    private static final Set<String> COMPARE_CHAT_POSTS = Set.of("/api/v1/ai/compare-chat/rank",
            "/api/v1/ai/compare-chat/explain", "/api/v1/ai/compare-chat/ask");

    /** 객체 검사된 기존 API만 개방한다. 후속 쓰기 API는 공통 범위 검증을 연결한 PR에서 연다. */
    public static final AuthorizationManager<RequestAuthorizationContext> SCOPED_READS = (authentication, context) -> {
        var request = context.getRequest();
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (HttpMethod.POST.matches(request.getMethod())) {
            // FUN-079: 설계사 ID를 세션에서만 얻고 서버가 보관한 본인 결과만 쓰는 조회성 계산·질문 답변만 POST를 연다.
            return new AuthorizationDecision(COMPARE_CHAT_POSTS.contains(path));
        }
        return new AuthorizationDecision(HttpMethod.GET.matches(request.getMethod()) && (
                PUBLIC_READS.contains(path)
                || path.matches("/api/v1/contracts(?:/export\\.csv|/[1-9]\\d*(?:/status-events)?)?")
                || path.matches("/api/v1/transactions(?:/[1-9]\\d*)?")));
    };

    private AgentRequestAuthorization() { }
}
