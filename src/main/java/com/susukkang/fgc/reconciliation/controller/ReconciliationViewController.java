package com.susukkang.fgc.reconciliation.controller;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunHistoryResponse;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunSearchCriteria;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunSearchResponse;
import com.susukkang.fgc.reconciliation.service.ReconciliationRunHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.YearMonth;

/**
 * FGC-UI-RECO-W01(대사 실행·결과) MPA 골격 (FUN-048~051, #205).
 * 인터페이스정의서 5-2 라우팅표: RECO-W01은 ④ 실행 이력만 MPA(IF-API-39)이고, ①실행·③결과
 * 목록·RECO-W02 비교상세는 전부 Ajax(SIR-006)다 — ValidationRunViewController/W02와 같은
 * "MPA 골격 + 부분 Ajax" 역할 분담.
 *
 * month는 ShellAdvice가 셸 헤더의 기준월(세션 "fgc.month")을 항상 유효한 값으로 채워
 * 넘겨준다 — 화면의 ① 실행 영역 정산월 입력도 이 값을 그대로 쓴다(reco/list.html의
 * r-month가 th:value="${month}").
 */
@Controller
@RequiredArgsConstructor
public class ReconciliationViewController {

    /** 화면정의서 §4-1 공통 규칙 7 — 목록은 서버에서 20행씩. */
    private static final int PAGE_SIZE = 20;

    private final ReconciliationRunHistoryService reconciliationRunHistoryService;

    @GetMapping("/reconciliations")
    public String list(
            @ModelAttribute("month") String month,
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) Long resultId,
            @RequestParam(defaultValue = "1") int page,
            Model model
    ) {
        // 잘못된 stage는 조용히 정상화 — MPA는 업무 예외를 던지지 않는다(AuditLogViewController 전례).
        String safeStage = null;
        if (stage != null && !stage.isBlank()) {
            try {
                safeStage = PaymentStage.valueOf(stage).name();
            } catch (IllegalArgumentException ignored) {
                // 전체 조회로 되돌림
            }
        }
        page = Math.max(1, Math.min(page, Integer.MAX_VALUE / PAGE_SIZE));

        var settlementMonth = YearMonth.parse(month).atDay(1);
        var criteria = new ReconciliationRunSearchCriteria(settlementMonth, safeStage);
        PageResponse<ReconciliationRunHistoryResponse> history =
                reconciliationRunHistoryService.findHistory(criteria, page, PAGE_SIZE, "createdAt,desc");

        model.addAttribute("history", ReconciliationRunSearchResponse.from(history));
        model.addAttribute("stageFilter", safeStage);
        model.addAttribute("stageOptions", PaymentStage.values());
        model.addAttribute("deepLinkedResultId", resultId);
        return "reco/list";
    }
}
