package com.susukkang.fgc.policy.controller;

import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.policy.service.PolicyQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;

/**
 * 설명 : POL-W01 정책·룰셋 조회 화면 (FGC-FUN-012·013).
 * 목록은 MPA 서버 렌더(IF-API-09 데이터), 탭 상세는 화면 스크립트가
 * IF-API-10(/api/v1/policies/{id})을 탭 클릭 시 Ajax 호출한다 — 인터페이스정의서 §5-2.
 * 기준일(asOf) 미지정 시 기준 정산월 1일을 쓴다 — 화면정의서 POL-W01
 * "정책은 계약일 기준으로 고르는 경우가 많다. 조회 조건에 기준일을 꼭 넣으세요."
 */
@Controller
@RequiredArgsConstructor
public class PolicyViewController {

    private final PolicyQueryService policyQueryService;

    @GetMapping("/policies")
    public String policies(
            @ModelAttribute("month") String month,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            Model model
    ) {
        LocalDate effectiveAsOf = asOf != null ? asOf : DateUtil.parseSettlementMonth(month);
        model.addAttribute("asOf", effectiveAsOf);
        model.addAttribute("policyVersions",
                policyQueryService.findPolicyVersions(null, effectiveAsOf, null));
        return "policy/list";
    }
}
