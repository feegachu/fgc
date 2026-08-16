package com.susukkang.fgc.exceptioncase.controller;

import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.exceptioncase.mapper.ExceptionCaseQueryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * FGC-UI-EXCP-W01 예외함 화면 (FUN-052·053).
 * 인터페이스정의서 5-2 라우팅표: GET /exceptions → exception/list.html.
 * 조회는 전체 역할(인터페이스정의서 IF-API-43), 처리 API(IF-API-44)만 SETTLEMENT·GA_ADMIN.
 *
 * 화면 필터 status=OPEN(미처리)은 exception_case.status 에 없는 묶음값이라
 * {@link ExceptionStatus#dbStatuses} 한 곳에서 NEW+IN_REVIEW 로 풀어 조회한다(#83).
 * 대시보드 KPI 카드 링크와 이 화면의 상태 select 가 같은 OPEN 어휘를 쓰고 서버가 푼다.
 *
 * 카드 링크가 같이 보내는 month 는 예외의 검색조건이 아니라 셸 기준월이다 —
 * {@link com.susukkang.fgc.common.web.ShellAdvice} 가 세션에 반영한다. KPI '미처리 예외'
 * 집계(DashboardMapper.countOpenException)에도 월 필터가 없으므로 카드 건수와
 * 이 화면의 미처리 건수는 그대로 맞는다.
 */
@Controller
@RequiredArgsConstructor
public class ExceptionCaseViewController {

    private final ExceptionCaseQueryMapper exceptionCaseQueryMapper;

    @GetMapping("/exceptions")
    public String list(@RequestParam(required = false) String status, Model model) {
        // defaultValue 는 빈 문자열(status= → "전체")까지 OPEN 으로 덮어쓰므로 쓰지 않는다 —
        // 파라미터가 아예 없을 때만 워크큐 기본값(미처리)으로 연다.
        if (status == null) {
            status = ExceptionStatus.OPEN_FILTER;
        }
        List<ExceptionStatus> statuses;
        try {
            statuses = ExceptionStatus.dbStatuses(status);
        } catch (IllegalArgumentException e) {
            // ShellAdvice 의 month 처럼 조용히 기본 필터로 되돌린다 — 화면 select 가
            // 보내는 값이라 사용자가 직접 칠 일이 없고, 워크큐 기본값은 미처리다.
            status = ExceptionStatus.OPEN_FILTER;
            statuses = ExceptionStatus.dbStatuses(status);
        }
        model.addAttribute("statusFilter", status);
        model.addAttribute("cases", exceptionCaseQueryMapper.findCases(statuses));
        model.addAttribute("openCount", exceptionCaseQueryMapper.countByStatuses(
                ExceptionStatus.dbStatuses(ExceptionStatus.OPEN_FILTER)));
        return "exception/list";
    }
}
