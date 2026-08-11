package com.susukkang.fgc.common.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 1차 21면 중 아직 실데이터 바인딩 전인 화면들의 정적 라우팅.
 * 인터페이스정의서 5-2 라우팅표의 경로 → 템플릿 매핑을 그대로 따른다.
 *
 * 화면이 실데이터를 바인딩하는 시점(예: DASH-W01 의 {@link
 * com.susukkang.fgc.dashboard.controller.DashboardViewController})에
 * 해당 라우트를 도메인 뷰 컨트롤러로 옮기고 여기서 지운다 —
 * 기능 브랜치끼리 이 파일에서 충돌하지 않게 한 화면씩 빼 가면 된다.
 *
 * 경로변수({id} 등)는 정적 단계에서는 쓰지 않으므로 바인딩하지 않는다.
 */
@Controller
public class ScreenViewController {

    @GetMapping("/base")
    public String base() {
        return "base/index";
    }

    @GetMapping("/policies")
    public String policies() {
        return "policy/list";
    }

    @GetMapping("/contracts")
    public String contractList() {
        return "contract/list";
    }

    @GetMapping("/contracts/new")
    public String contractNew() {
        return "contract/form";
    }

    @GetMapping("/contracts/{id}/edit")
    public String contractEdit() {
        return "contract/form";
    }

    @GetMapping("/contracts/{id}")
    public String contractDetail() {
        return "contract/detail";
    }

    @GetMapping("/transactions")
    public String transactionList() {
        return "transaction/list";
    }

    @GetMapping("/transactions/new")
    public String transactionNew() {
        return "transaction/form";
    }

    @GetMapping("/schedules")
    public String scheduleList() {
        return "schedule/list";
    }

    @GetMapping("/schedules/{id}")
    public String scheduleDetail() {
        return "schedule/detail";
    }

    @GetMapping("/cap-checks")
    public String capCheckList() {
        return "cap/list";
    }

    @GetMapping("/arbitrage-checks")
    public String arbitrageList() {
        return "arbitrage/list";
    }

    @GetMapping("/journals")
    public String journalList() {
        return "ledger/list";
    }

    @GetMapping("/reconciliations")
    public String reconciliationList() {
        return "reco/list";
    }

    @GetMapping("/exceptions")
    public String exceptionList() {
        return "exception/list";
    }

    @GetMapping("/validation-runs")
    public String validationRunList() {
        return "vrun/list";
    }

    @GetMapping("/validation-runs/{id}")
    public String validationRunDetail() {
        return "vrun/detail";
    }

    @GetMapping("/audit-logs")
    public String auditLogList() {
        return "audit/list";
    }
}
