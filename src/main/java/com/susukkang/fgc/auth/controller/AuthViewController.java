package com.susukkang.fgc.auth.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// FUN-001 개발 순서 7

/**
 * AUTH-W01 로그인 화면(인터페이스정의서 4-1 라우팅표: GET /login → auth/login.html).
 *
 * POST /login, POST /logout(IF-API-01·02)은 Spring Security 필터가 처리한다 — 여기 핸들러가 없다.
 */
@Controller
public class AuthViewController {

    @GetMapping("/login")
    public String loginPage() {
        return "auth/login";
    }
}
