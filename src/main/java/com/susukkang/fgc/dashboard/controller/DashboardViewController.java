package com.susukkang.fgc.dashboard.controller;

import com.susukkang.fgc.dashboard.dto.DashboardSummaryResponse;
import com.susukkang.fgc.dashboard.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.time.YearMonth;

/**
 * FGC-UI-DASH-W01(FUN-057) 업무 대시보드 화면.
 * 인터페이스정의서 5-2 라우팅표: GET / → dashboard/index.html
 *
 * REST(IF-API-03)를 HTTP 로 다시 부르지 않고 {@link DashboardService} 를 그대로 쓴다.
 * 같은 JVM 안에서 자기 API 를 호출하면 직렬화·역직렬화와 인증을 한 번 더 태우는 값만 든다.
 *
 * 기준월은 {@link com.susukkang.fgc.common.web.ShellAdvice} 가 세션에 넣어 둔 값을 받는다 —
 * 헤더 select 를 바꾸면 ?month= 로 돌아와 이 화면이 다시 집계된다(SIR-006: 1차는 MPA 전체 갱신).
 */
@Controller
@RequiredArgsConstructor
public class DashboardViewController {

    private final DashboardService dashboardService;

    @GetMapping("/")
    public String index(@ModelAttribute("month") String month, Model model) {
        model.addAttribute("summary", DashboardSummaryResponse.from(
                dashboardService.summarize(YearMonth.parse(month).atDay(1))));
        return "dashboard/index";
    }
}
