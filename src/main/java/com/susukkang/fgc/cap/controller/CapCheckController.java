package com.susukkang.fgc.cap.controller;

import com.susukkang.fgc.cap.dto.CapCheckDetailPopupResponse;
import com.susukkang.fgc.cap.dto.CapCheckSaveResult;
import com.susukkang.fgc.cap.dto.CapCheckSearchCriteria;
import com.susukkang.fgc.cap.dto.CapCheckSearchResponse;
import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.Map;

/**
 * FGC-FUN-030 초년도 1,200% 한도 계산 조회 API — CAP-W01(목록) / CAP-W02(계산근거 팝업).
 * 계산·저장 자체(CapCheckService#calculateAndSave)는 이 컨트롤러가 아니라 ContractIssued 등 내부
 * 이벤트 리스너가 호출한다(인터페이스정의서 6장 "①즉시재계산") — 이 API는 조회 전용이다.
 */
@Tag(name = "1200% 한도", description = "초년도 모집수수료 한도 계산 조회 API")
@RestController
@RequestMapping("/api/cap/checks")
@RequiredArgsConstructor
public class CapCheckController {

    private final CapCheckService capCheckService;

    @Operation(
            summary = "1,200% 한도 판정 목록 조회 (IF-API-30)",
            description = "정산월·지급단계·판정·보험회사·계약번호로 검색하고, 요약 카드 4장(정상/주의/위반/검토필요)과 "
                    + "함께 페이징된 목록을 돌려준다."
    )
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<CapCheckSearchResponse> search(
            @Parameter(description = "정산월(yyyy-MM). as_of_date가 속한 달로 거른다", example = "2026-07")
            @RequestParam(required = false) String month,
            @Parameter(description = "지급단계") @RequestParam(required = false) PaymentStage stage,
            @Parameter(description = "판정") @RequestParam(required = false) CapResultStatus status,
            @Parameter(description = "보험회사 ID") @RequestParam(required = false) Long insurerId,
            @Parameter(description = "계약번호") @RequestParam(required = false) String contractNo,
            @Parameter(description = "페이지(1-base)") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "페이지 크기(최대 100)") @RequestParam(defaultValue = "20") int size
    ) {
        CapCheckSearchCriteria criteria = new CapCheckSearchCriteria(
                month == null ? null : YearMonth.parse(month).atDay(1),
                stage == null ? null : stage.name(),
                status == null ? null : status.name(),
                insurerId,
                contractNo);

        return ApiResponse.success(CapCheckSearchResponse.from(capCheckService.search(criteria, page, size)));
    }

    @Operation(
            summary = "1,200% 계산근거 상세 조회 (IF-API-31)",
            description = "검증 당시 저장된 cap_check/cap_check_detail/calculation_snapshot 스냅샷을 그대로 돌려준다. "
                    + "다시 계산하지 않는다(CAP-W02)."
    )
    @GetMapping("/{capCheckId}/details")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<CapCheckDetailPopupResponse> findDetails(
            @Parameter(description = "cap_check ID", example = "999")
            @PathVariable Long capCheckId
    ) {
        // capCheckId는 항상 IF-API-30 목록에서 클릭해 들어오므로, 존재하지 않는 ID는 정상 사용자
        // 흐름에서 생기지 않는 시스템 상황이다. 인터페이스정의서 3-2절 동결 오류코드 표에 이 상황
        // 전용 코드가 없어 COMMON_500을 쓴다(CapCalculatorImpl의 같은 판단과 동일).
        CapCheckSaveResult saved = capCheckService.findById(capCheckId)
                .orElseThrow(() -> new FgcBusinessException(FgcErrorCode.COMMON_500,
                        Map.of("capCheckId", capCheckId)));
        return ApiResponse.success(CapCheckDetailPopupResponse.from(saved));
    }
}
