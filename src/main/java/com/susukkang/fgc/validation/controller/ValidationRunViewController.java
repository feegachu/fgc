package com.susukkang.fgc.validation.controller;

import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.validation.dto.CreateValidationRunCommand;
import com.susukkang.fgc.validation.dto.ValidationRunDetailResponse;
import com.susukkang.fgc.validation.dto.ValidationRunListRow;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.dto.ValidationRunSearchCriteria;
import com.susukkang.fgc.validation.dto.ValidationRunSearchResponse;
import com.susukkang.fgc.validation.service.ValidationRunCreateService;
import com.susukkang.fgc.validation.service.ValidationRunDetailService;
import com.susukkang.fgc.validation.service.ValidationRunSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * FGC-UI-VRUN-W01(실행 목록)·W02(상세) 서버 렌더링 (FUN-041~043).
 * 인터페이스정의서 5-2 라우팅표: VRUN-W01 은 Ajax 없음 — 목록·생성 가능 여부·생성(PRG)까지
 * 전부 서버 렌더링/폼 제출이다. W02 는 MPA 골격 + 실행(IF-API-48)·진행률(IF-API-49)만 Ajax.
 *
 * MPA @Controller 는 GlobalExceptionHandler(@RestController 한정) 밖이라 업무 예외를
 * 던지면 일반 500 화면이 된다 — 잘못된 필터값은 조용히 정상화하고(AuditLogViewController
 * 전례), 생성 실패(VRUN_001 등)는 플래시 배너로 안내한다.
 */
@Controller
@RequiredArgsConstructor
public class ValidationRunViewController {

    /** 화면정의서 §4-1 공통 규칙 7 — 목록은 서버에서 20행씩. */
    private static final int PAGE_SIZE = 20;

    /**
     * 운영정책서 제43조 10단계 이름 — DB 가 아니라 화면 상수다(화면정의서 :1400~1409).
     * 9·10단계는 배치가 아니라 사람이 수행한다.
     */
    private static final List<String> STEP_NAMES = List.of(
            "실행 생성", "대상 선별", "스케줄 생성·재검증", "1,200% 검증", "차익거래 검증",
            "원장 기표·균형", "양방향 대사", "예외 생성", "담당자 검토", "확정");

    private final ValidationRunSearchService validationRunSearchService;
    private final ValidationRunCreateService validationRunCreateService;
    private final ValidationRunDetailService validationRunDetailService;
    private final FgcMessageResolver fgcMessageResolver;

    @GetMapping("/validation-runs")
    public String list(
            @ModelAttribute("month") String month,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            Model model
    ) {
        // 잘못된 status/page 는 조용히 정상화 — MPA 는 업무 예외를 던지지 않는다.
        String safeStatus = null;
        if (status != null && !status.isBlank()) {
            try {
                safeStatus = ValidationRunStatus.valueOf(status).name();
            } catch (IllegalArgumentException ignored) {
                // 전체 조회로 되돌림
            }
        }
        page = Math.max(1, Math.min(page, Integer.MAX_VALUE / PAGE_SIZE));

        PageResponse<ValidationRunListRow> runs = validationRunSearchService.search(
                new ValidationRunSearchCriteria(null, safeStatus), page, PAGE_SIZE);

        // 생성 버튼 비활성 판정은 셸 기준월(ShellAdvice) 기준 — 사용자가 폼에서 다른 달을
        // 고르면 서버의 VRUN_001 이 최종 방어한다(화면정의서 :1379 "DB가 막습니다").
        LocalDate shellMonth = YearMonth.parse(month).atDay(1);

        model.addAttribute("runs", ValidationRunSearchResponse.from(runs));
        model.addAttribute("statusFilter", safeStatus);
        model.addAttribute("statusOptions", ValidationRunStatus.values());
        model.addAttribute("existsActiveMonthly",
                validationRunSearchService.existsActiveMonthlyRun(shellMonth));
        return "vrun/list";
    }

    /**
     * IF-API-45 와 같은 생성 규칙의 MPA 경로(폼 제출·PRG). 성공하면 상세로 — 다음 행동이
     * [실행] 버튼이기 때문이다.
     */
    @PreAuthorize(Roles.CAN_PROCESS)
    @PostMapping("/validation-runs")
    public String create(
            @RequestParam String month,
            @RequestParam String runType,
            @AuthenticationPrincipal FgcUserDetails principal,
            RedirectAttributes redirectAttributes
    ) {
        LocalDate validationMonth;
        ValidationRunType type;
        try {
            validationMonth = DateUtil.parseSettlementMonth(month);
            type = ValidationRunType.valueOf(runType);
        } catch (DateTimeException | IllegalArgumentException | NullPointerException e) {
            redirectAttributes.addFlashAttribute("errorMessage", "검증월(yyyy-MM)과 실행 유형을 확인하세요.");
            return "redirect:/validation-runs";
        }

        try {
            ValidationRunRow row = validationRunCreateService.create(
                    new CreateValidationRunCommand(validationMonth, type, principal.getUserId()));
            redirectAttributes.addFlashAttribute("successMessage",
                    DateUtil.formatSettlementMonth(row.getValidationMonth())
                            + " 검증월 " + row.getRunNo() + "회차 실행이 생성되었습니다.");
            return "redirect:/validation-runs/" + row.getValidationRunId();
        } catch (FgcBusinessException e) {
            // VRUN_001(활성 MONTHLY 중복) 등 — 500 화면 대신 목록 배너로 안내
            redirectAttributes.addFlashAttribute("errorMessage",
                    fgcMessageResolver.resolve(e.getErrorCode(), e.getParams()));
            return "redirect:/validation-runs";
        }
    }

    @GetMapping("/validation-runs/{id}")
    public String detail(@PathVariable Long id, Model model) {
        ValidationRunDetailResponse detail;
        try {
            detail = validationRunDetailService.detail(id);
        } catch (FgcBusinessException e) {
            // 없는 리소스는 필터값 정상화 대상이 아니다 — COMMON_004 만 404 로 바꾸고,
            // 다른 업무 예외는 삼키지 않는다(원인 보존).
            if (e.getErrorCode() == FgcErrorCode.COMMON_004) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, null, e);
            }
            throw e;
        }

        // pick-run 선택지 — 최근 실행 20건이면 화면 목적(빠른 이동)에 충분
        PageResponse<ValidationRunListRow> runOptions = validationRunSearchService.search(
                new ValidationRunSearchCriteria(null, null), 1, PAGE_SIZE);

        model.addAttribute("detail", detail);
        model.addAttribute("runOptions", ValidationRunSearchResponse.from(runOptions));
        model.addAttribute("stepNames", STEP_NAMES);
        // 스텝 칸 CSS 는 여기서 계산한다 — BEM 클래스명(fgc-stepper__step--done)의 "__" 를
        // Thymeleaf 가 전처리 식(__...__)으로 해석해 템플릿 표현식 안에 둘 수 없다.
        // vrun-detail.js 의 stepClass() 와 같은 규칙이다.
        model.addAttribute("stepClasses", stepClasses(
                detail.header().status().name(), detail.header().currentStep()));
        return "vrun/detail";
    }

    private static List<String> stepClasses(String status, int currentStep) {
        return java.util.stream.IntStream.rangeClosed(1, STEP_NAMES.size())
                .mapToObj(stepNo -> stepClass(stepNo, status, currentStep))
                .toList();
    }

    /** current_step 은 "마지막으로 끝난 단계" — RUNNING 중이면 그 다음 칸이 진행 중이다. */
    private static String stepClass(int stepNo, String status, int currentStep) {
        if ("FAILED".equals(status) && stepNo == currentStep) {
            return "fgc-stepper__step--failed";
        }
        // FINALIZED 는 ck_validation_run_step 이 current_step=10 을 강제하므로
        // 10칸 전부 done 이 곧 데이터 사실이다(조건을 currentStep 과 무관하게 둔 이유).
        if ("FINALIZED".equals(status)) {
            return "fgc-stepper__step--done";
        }
        boolean batchTouched = "RUNNING".equals(status) || "COMPLETED".equals(status) || "FAILED".equals(status);
        if (batchTouched && stepNo <= currentStep) {
            return "fgc-stepper__step--done";
        }
        if ("RUNNING".equals(status) && stepNo == currentStep + 1 && stepNo <= 8) {
            return "fgc-stepper__step--running";
        }
        return "";
    }
}
