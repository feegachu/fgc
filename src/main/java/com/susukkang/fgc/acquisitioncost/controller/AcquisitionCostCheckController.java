package com.susukkang.fgc.acquisitioncost.controller;

import com.susukkang.fgc.acquisitioncost.dto.AcquisitionCostCheckRequest;
import com.susukkang.fgc.acquisitioncost.dto.AcquisitionCostCheckResponse;
import com.susukkang.fgc.acquisitioncost.dto.AcquisitionCostCheckSearchCondition;
import com.susukkang.fgc.acquisitioncost.service.AcquisitionCostCheckService;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** FUN-062·ACQC-W01. AGENT는 AgentRequestAuthorization 허용 목록에 없어 이 경로에 접근할 수 없다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/acquisition-cost-checks")
public class AcquisitionCostCheckController {
    private final AcquisitionCostCheckService service;

    @GetMapping
    @PreAuthorize(Roles.ANY_ROLE)
    @Operation(operationId = "searchAcquisitionCostChecks", summary = "FUN-062 계약체결비용 한도 검증 결과 목록",
            description = "저장한 결과를 최신순으로 돌려줍니다. 조회만으로 검증을 만들지 않습니다.")
    public ApiResponse<PageResponse<AcquisitionCostCheckResponse>> search(
            @ModelAttribute AcquisitionCostCheckSearchCondition condition,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.search(condition, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize(Roles.ANY_ROLE)
    @Operation(operationId = "getAcquisitionCostCheck", summary = "FUN-062 계약체결비용 한도 검증 결과 상세")
    public ApiResponse<AcquisitionCostCheckResponse> detail(@PathVariable long id) {
        return ApiResponse.success(service.detail(id));
    }

    @PostMapping
    @PreAuthorize(Roles.CAN_PROCESS)
    @Operation(operationId = "requestAcquisitionCostCheck", summary = "FUN-062 계약체결비용 한도 검증 요청",
            description = "계약의 활성 예정 스케줄을 정책 기준으로 판정해 결과를 저장합니다. 자료가 부족하면 오류가 아니라 "
                    + "금액 null과 REVIEW_REQUIRED로 저장합니다. 같은 Idempotency-Key 재요청은 최초 결과를 돌려줍니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201", description = "검증 결과", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "멱등키 충돌 또는 같은 요청 처리 중")
    })
    public ResponseEntity<ApiResponse<AcquisitionCostCheckResponse>> request(
            @Valid @RequestBody AcquisitionCostCheckRequest request,
            @AuthenticationPrincipal FgcUserDetails principal,
            @Parameter(description = "검증 요청의 멱등키", required = true)
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        AcquisitionCostCheckResponse result = service.request(request, idempotencyKey, principal.getUserId());
        return ResponseEntity.created(URI.create("/api/v1/acquisition-cost-checks/" + result.acquisitionCostCheckId()))
                .body(ApiResponse.success(result));
    }
}
