package com.susukkang.fgc.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.auth.service.FgcUserDetailsService;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;

/** FUN-085: 역할 변경 전 세션의 내부 권한 재사용을 차단한다. URL 권한 규칙은 유지한다. */
public class SessionRoleValidationFilter extends OncePerRequestFilter {
    private final ObjectProvider<FgcUserDetailsService> users;
    private final GlobalExceptionHandler errors;
    private final ObjectMapper mapper;

    public SessionRoleValidationFilter(ObjectProvider<FgcUserDetailsService> users,
                                       GlobalExceptionHandler errors, ObjectMapper mapper) {
        this.users = users;
        this.errors = errors;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var session = request.getSession(false);
        // 헤더만으로 생략하면 Bearer 필터가 없는 MPA 체인에서 오래된 세션을 재사용할 수 있다.
        if (session == null || authentication == null
                || !(authentication.getPrincipal() instanceof FgcUserDetails previous)) {
            chain.doFilter(request, response);
            return;
        }
        // MVC slice에는 사용자 조회 빈이 없다. 실제 애플리케이션에는 인증 Provider와 같은 빈이 있다.
        var service = users.getIfAvailable();
        if (service == null) {
            chain.doFilter(request, response);
            return;
        }
        try {
            FgcUserDetails current = (FgcUserDetails) service.loadUserByUsername(authentication.getName());
            new AccountStatusUserDetailsChecker().check(current);
            if (!Objects.equals(previous.getUserId(), current.getUserId())
                    || !Objects.equals(previous.getRoleCode(), current.getRoleCode())) {
                throw new CredentialsExpiredException("Session identity or role changed");
            }
        } catch (AuthenticationException exception) {
            SecurityContextHolder.clearContext();
            session.invalidate();
            var result = errors.handleBusinessException(new FgcBusinessException(FgcErrorCode.AUTH_002));
            response.setStatus(result.getStatusCode().value());
            response.setCharacterEncoding("UTF-8");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getWriter(), result.getBody());
            return;
        }
        chain.doFilter(request, response);
    }
}
