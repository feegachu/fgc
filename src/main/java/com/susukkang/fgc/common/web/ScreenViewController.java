package com.susukkang.fgc.common.web;

import com.susukkang.fgc.common.code.JournalHeaderStatus;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.journal.domain.JournalType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

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

    /**
     * CONT-W03 — 인터페이스정의서 IF-API-18/19 역할 SETTLEMENT (+SYSTEM_ADMIN 은 전부, §4-1).
     * FGC-FUN-002 인수조건: 직접 URL 호출도 403 으로 차단된다.
     */
    @GetMapping("/contracts/{id}")
    public String contractDetail() {
        return "contract/detail";
    }

    @GetMapping("/transactions")
    public String transactionList() {
        return "transaction/list";
    }

    /** TRAN-W02 — 화면정의서 :686 역할 SETTLEMENT (+SYSTEM_ADMIN 은 전부, §4-1). */
    @PreAuthorize(Roles.CAN_PROCESS)
    @GetMapping("/transactions/new")
    public String transactionNew() {
        return "transaction/form";
    }

    @GetMapping("/schedules")
    public String scheduleList() {
        return "schedule/list";
    }

    @GetMapping("/schedules/{id}")
    public String scheduleDetail(@PathVariable Long id, Model model) {
        model.addAttribute("id", id);
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
    public String journalList(Model model) {
        // 2026-08-19 yslee - LEDG-W01 검색 선택지를 도메인 enum에서 제공
        // 기존 코드: 화면에 비어 있는 select만 있어 분개유형·상태 검색을 사용할 수 없음
        // 문제: 프론트에 enum 값을 하드코딩하면 서버 도메인과 변경 시점이 어긋남
        // 개선: 서버 도메인 값을 모델로 전달해 IF-API-34 검색조건과 같은 값을 사용
        model.addAttribute("journalTypes", JournalType.values());
        model.addAttribute("journalStatuses", JournalHeaderStatus.values());
        return "ledger/list";
    }

    // AUDT-W01(/audit-logs)은 데이터 바인딩과 함께 audit.controller.AuditLogViewController 로,
    // VRUN-W01/W02(/validation-runs)는 validation.controller.ValidationRunViewController 로,
    // RECO-W01(/reconciliations)은 reconciliation.controller.ReconciliationViewController 로 이관했다.
}
