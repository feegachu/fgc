package com.susukkang.fgc.common.web;

import com.susukkang.fgc.auth.dto.FgcUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.YearMonth;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 공통 셸(layout/default.html)이 필요로 하는 모델 속성을 모든 화면에 넣어 준다.
 *
 * 목업의 assets/fgc-shell.js 가 하던 3가지를 서버로 옮긴 것이다.
 *   sessionStorage['fgc.month']        → HttpSession "fgc.month"      ({@link #month})
 *   기준월 <select> + fgc:month-change → ?month= 전체 리로드           (layout/default.html)
 *   role==COMPLIANCE 면 버튼 disable   → {@link #readOnly} + th:disabled
 *
 * 세션을 만들지 않는 이유
 *  HttpSession 을 파라미터로 받으면 없을 때 새로 만들어 버린다. 이 advice 는 @RestController 에도
 *  걸리므로(@RestController 는 @Controller 의 메타 애노테이션) httpBasic 으로 들어온 /api/** 호출마다
 *  빈 세션이 쌓인다. request.getSession(false) 로 "있으면 쓰고 없으면 만들지 않는다".
 */
// 셸에 데이터를 제공하는 @ControllerAdvice
@ControllerAdvice
public class ShellAdvice {

    /** 화면에 띄울 기준월 후보 개수. 목업 헤더와 같게 기준월 앞 2개 + 뒤 1개. */
    private static final int MONTHS_BEFORE = 2;
    private static final int MONTHS_AFTER = 1;

    private static final String SESSION_KEY = "fgc.month";

    /** COR-004 — 기준월을 코드에 박지 않는다. application.yml 의 fgc.demo-month. */
    private final YearMonth demoMonth;

    public ShellAdvice(@Value("${fgc.demo-month}") String demoMonth) {
        this.demoMonth = YearMonth.parse(demoMonth);
    }

    /**
     * 기준 정산월(yyyy-MM). ?month= 로 바꾸면 세션에 남아 다음 화면까지 따라간다.
     *
     * 형식이 틀린 month 는 400 을 던지지 않고 조용히 기본값으로 되돌린다. 헤더 select 가 보내는
     * 값이라 사용자가 직접 입력할 일이 없고, 셸이 죽으면 화면 전체가 안 뜨기 때문이다.
     * 값을 실제로 쓰는 컨트롤러(예: 대시보드)가 자기 규칙대로 다시 검증한다.
     */
    @ModelAttribute("month")
    public String month(@RequestParam(required = false) String month, HttpServletRequest request) {
        if (month != null && parse(month) != null) {
            request.getSession().setAttribute(SESSION_KEY, month);
            return month;
        }
        HttpSession session = request.getSession(false);
        Object saved = session == null ? null : session.getAttribute(SESSION_KEY);
        return saved instanceof String s && parse(s) != null ? s : demoMonth.toString();
    }

    @ModelAttribute("monthOptions")
    public List<String> monthOptions(@ModelAttribute("month") String month) {
        YearMonth base = parse(month);
        return IntStream.rangeClosed(-MONTHS_BEFORE, MONTHS_AFTER)
                .mapToObj(i -> base.plusMonths(i).toString())
                .toList();
    }

    /** 준법·감사(COMPLIANCE)는 조회만 한다. 화면은 이 한 값만 보고 처리 버튼을 잠근다. */
    @ModelAttribute("readOnly")
    public boolean readOnly(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_COMPLIANCE".equals(a.getAuthority()));
    }

    /** 셸 헤더·사이드바의 역할 배지. messages.properties 의 role.{code} 키로 라벨을 찾는다. */
    @ModelAttribute("roleCode")
    public String roleCode(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof FgcUserDetails user
                ? user.getRoleCode()
                : "COMPLIANCE";
    }

    private static YearMonth parse(String value) {
        try {
            return YearMonth.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
