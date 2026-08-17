package com.susukkang.fgc.transaction.controller;

import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.transaction.dto.*;
import com.susukkang.fgc.transaction.service.CommissionPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 설명 : 수수료 지급 건 등록·수정·확정 REST API
 *
 * @author yslee
 * @since 2026-08-06
 * @version 1.2
 */
@Tag(name = "수수료 지급 건", description = "FUN-065 수수료 지급 건 등록·수정·확정 API")
@RestController
// 2026-08-10 yslee - 인터페이스정의서의 지급 거래 리소스명 적용
// 기존 코드: /api/v1/commission-payments 경로 사용
// 문제: IF-API-22·23·25와 TRAN-W02가 정의한 /api/v1/transactions 계약과 불일치
// 개선: 지급 건 등록·수정·확정 API를 /api/v1/transactions로 통일
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
// 2026-08-11 yslee - 지급 API 권한을 역할 매트릭스의 정산담당자·시스템관리자로 제한
// 기존 코드: SETTLEMENT만 허용하여 모든 기능 권한을 가진 SYSTEM_ADMIN도 접근 차단
// 문제: 인터페이스 정의서의 SYSTEM_ADMIN 전부 권한과 FUN-065 API 인가가 불일치
// 개선: SETTLEMENT 업무권한을 유지하면서 SYSTEM_ADMIN의 전체관리 권한도 허용
public class CommissionPaymentApiController {

    private final CommissionPaymentService commissionPaymentService;

    
    /**
     * 설명 : 수수료 계약건 조회
     *
     * @param  condition
     * @return 수수료 계약건 LIST
     * @author hjKang
     * @since 2026-08-16
     */
    @GetMapping
    public ApiResponse<PageResponse<CommissionPaymentListResponse>> search(
            @ModelAttribute CommissionPaymentSearchCondition condition,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.success(
                commissionPaymentService.search(condition, page, size)
        );
    }

    @GetMapping("/{paymentId}")
    @PreAuthorize(Roles.CAN_PROCESS)
    public ApiResponse<CommissionPaymentResponse> get(@PathVariable Long paymentId) {
        return ApiResponse.success(commissionPaymentService.get(paymentId));
    }

    // 2026-08-07 yslee - 지급 건 API의 성공·실패 계약을 Swagger 응답 명세로 보강
    // 기존 코드: 작업 요약만 표시되어 자연키 중복·증빙 누락·한도 초과 응답을 구분하기 어려움
    // 문제: API 사용자가 상태 코드별 업무 오류를 별도 Markdown 문서 없이 확인할 수 없음
    // 개선: 등록·수정·확정 Operation에 주요 성공 및 업무 오류 상태를 명시
    @Operation(
            summary = "수수료 지급 건 DRAFT 등록",
            description = "필수값과 계약·설계사·수수료 항목을 검증한 후 DRAFT로 저장합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201", description = "DRAFT 등록 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "필수값·귀속·증빙 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "지급 건 자연키 중복"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "정산담당자 권한 없음")
    })
    @PostMapping
    @PreAuthorize(Roles.CAN_PROCESS)
    public ResponseEntity<ApiResponse<CommissionPaymentResponse>> create(
            @Valid @RequestBody CommissionPaymentCreateRequest request
    ) {
        CommissionPaymentResponse response = commissionPaymentService.create(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }

    @Operation(
            summary = "DRAFT 수수료 지급 건 수정",
            description = "DRAFT 지급 건의 필수값과 참조 정보를 검증한 후 변경 내용을 저장합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "DRAFT 수정 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "필수값·귀속·증빙 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "DRAFT가 아니거나 자연키 중복"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "정산담당자 권한 없음")
    })
    @PutMapping("/{paymentId}")
    @PreAuthorize(Roles.CAN_PROCESS)
    public ApiResponse<CommissionPaymentResponse> update(
            @Parameter(description = "지급 건 ID", required = true)
            @PathVariable Long paymentId,
            @Valid @RequestBody CommissionPaymentUpdateRequest request
    ) {
        return ApiResponse.success(
                commissionPaymentService.update(paymentId, request)
        );
    }

    @Operation(
            summary = "수수료 지급 건 확정",
            description = "FUN-033 사전 한도 검증을 수행하고 성공한 DRAFT만 CONFIRMED로 전환합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "확정 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "배부 근거 또는 제외 증빙 누락"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "DRAFT 상태가 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422", description = "FUN-033 한도 초과 또는 정책 검토 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "정산담당자 권한 없음")
    })
    @PostMapping("/{paymentId}/confirm")
    @PreAuthorize(Roles.CAN_PROCESS)
    public ApiResponse<CommissionPaymentResponse> confirm(
            @Parameter(description = "지급 건 ID", required = true)
            @PathVariable Long paymentId,
            @Parameter(description = "확정 재요청 중복 방지 키")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.success(commissionPaymentService.confirm(paymentId, idempotencyKey));
    }

    @Operation(
            summary = "지급 전 한도 사전검증 미리보기 (IF-API-24)",
            description = "저장·상태 변경 없이 DRAFT 지급 건을 제31조 확정 게이트로 검사해 "
                    + "계약·지급단계별 1,200% 게이지(capPreview)와 확정 차단 사유(blockers)를 반환합니다. "
                    + "차단 사유가 있어도 200으로 응답합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "사전검증 결과 (차단 사유가 있어도 200 + blockers[])",
                    content = @Content(schema = @Schema(implementation = TransactionPrecheckResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "지급 건 없음 (FGC-COMMON-002)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "DRAFT 상태가 아님 (FGC-TRAN-005)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "정산담당자 권한 없음")
    })
    @PostMapping("/{paymentId}/precheck")
    @PreAuthorize(Roles.CAN_PROCESS)
    public ApiResponse<TransactionPrecheckResponse> precheck(
            @Parameter(description = "지급 건 ID", required = true)
            @PathVariable Long paymentId
    ) {
        return ApiResponse.success(commissionPaymentService.precheck(paymentId));
    }
}
