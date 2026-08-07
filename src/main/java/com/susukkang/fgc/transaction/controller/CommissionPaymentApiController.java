package com.susukkang.fgc.transaction.controller;

import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.transaction.dto.CommissionPaymentCreateRequest;
import com.susukkang.fgc.transaction.dto.CommissionPaymentResponse;
import com.susukkang.fgc.transaction.dto.CommissionPaymentUpdateRequest;
import com.susukkang.fgc.transaction.service.CommissionPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 설명 : 수수료 지급 건 등록·수정·확정 REST API
 *
 * @author yslee
 * @since 2026-08-06
 * @version 1.2
 */
@Tag(name = "수수료 지급 건", description = "FUN-065 수수료 지급 건 등록·수정·확정 API")
@RestController
// 2026-08-07 yslee - 미확정 API 버전 접두사를 지급 건 경로에서 제거
// 기존 코드: 미확정 버전 접두사가 포함된 지급 건 경로를 사용
// 문제: 인터페이스 정의서에서 API 버전 경로 도입이 검토 중이므로 현재 확정 경로로 사용할 수 없음
// 개선: 현재 프로젝트 API 규칙에 맞춰 /api/commission-payments로 통일
@RequestMapping("/api/commission-payments")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SETTLEMENT')")
public class CommissionPaymentApiController {

    private final CommissionPaymentService commissionPaymentService;

    // 2026-08-07 yslee - 지급 건 API의 성공·실패 계약을 Swagger 응답 명세로 보강
    // 기존 코드: 작업 요약만 표시되어 자연키 중복·증빙 누락·한도 초과 응답을 구분하기 어려움
    // 문제: API 사용자가 상태 코드별 업무 오류를 별도 Markdown 문서 없이 확인할 수 없음
    // 개선: 등록·수정·확정 Operation에 주요 성공 및 업무 오류 상태를 명시
    @Operation(
            summary = "수수료 지급 건 DRAFT 등록",
            description = "계약·설계사·수수료 항목과 FUN-033 한도를 검증한 후 DRAFT로 저장합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201", description = "DRAFT 등록 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "필수값·귀속·증빙 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "지급 건 자연키 중복"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422", description = "FUN-033 한도 초과 또는 정책 검토 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "정산담당자 권한 없음")
    })
    @PostMapping
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
            description = "DRAFT 지급 건만 수정하며 변경값으로 FUN-033 사전검증을 다시 수행합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "DRAFT 수정 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "필수값·귀속·증빙 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "DRAFT가 아니거나 자연키 중복"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422", description = "FUN-033 한도 초과 또는 정책 검토 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "정산담당자 권한 없음")
    })
    @PutMapping("/{paymentId}")
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
    public ApiResponse<CommissionPaymentResponse> confirm(
            @Parameter(description = "지급 건 ID", required = true)
            @PathVariable Long paymentId
    ) {
        return ApiResponse.success(commissionPaymentService.confirm(paymentId));
    }
}
