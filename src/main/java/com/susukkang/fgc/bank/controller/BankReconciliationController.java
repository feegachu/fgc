package com.susukkang.fgc.bank.controller;

import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.bank.dto.BankAuthorizeUrlResponse;
import com.susukkang.fgc.bank.dto.BankLinkRequest;
import com.susukkang.fgc.bank.dto.BankLinkResponse;
import com.susukkang.fgc.bank.dto.BankReconciliationRequest;
import com.susukkang.fgc.bank.dto.BankReconciliationResponse;
import com.susukkang.fgc.bank.service.BankLinkService;
import com.susukkang.fgc.bank.service.BankReconciliationService;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.common.web.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 설명 : FUN-090 지급 이체 대사 API(IF-API-95~99). 은행 계좌를 연결하고, 확정된 달의 확정 지급과 은행 출금을 맞춘다.
 * 조회는 내부 네 역할 모두, 은행 연결과 대사 실행은 정산 처리 역할만 한다. 은행에서는 조회만 한다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-11-18
 */
@Tag(name = "지급 이체 대사", description = "확정된 정산월의 확정 지급 순액과 은행 계좌의 실제 출금을 맞춘다")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class BankReconciliationController {

    private final BankLinkService linkService;
    private final BankReconciliationService reconciliationService;

    @Operation(summary = "은행 연결 상태", description = "연결한 수수료 지급 계좌(가린 번호)와 지금 은행을 부를 수 있는지 돌려준다.")
    @GetMapping("/bank/link")
    @PreAuthorize(Roles.ANY_ROLE)
    public ApiResponse<BankLinkResponse> linkStatus() {
        return ApiResponse.success(linkService.status());
    }

    @Operation(summary = "은행 동의 화면 주소",
            description = "브라우저를 보낼 은행 동의 화면 주소를 만든다. 주소에 넣은 state 는 요청한 사용자에게 10분 동안 묶인다.")
    @PostMapping("/bank/link/authorize-url")
    @PreAuthorize(Roles.CAN_PROCESS)
    public ApiResponse<BankAuthorizeUrlResponse> authorizeUrl(@AuthenticationPrincipal FgcUserDetails principal) {
        return ApiResponse.success(linkService.authorizeUrl(principal.getUserId()));
    }

    @Operation(summary = "은행 계좌 연결",
            description = "동의 뒤 은행이 돌려준 code 와 state 로 토큰을 받고, 조회에 동의한 첫 계좌를 수수료 지급 계좌로 연결한다.")
    @PostMapping("/bank/link")
    @PreAuthorize(Roles.CAN_PROCESS)
    public ApiResponse<BankLinkResponse> link(@Valid @RequestBody BankLinkRequest request,
                                              @AuthenticationPrincipal FgcUserDetails principal) {
        return ApiResponse.success(linkService.link(principal.getUserId(), request.code(), request.state()));
    }

    @Operation(summary = "지급 이체 대사 실행",
            description = "확정된 정산월의 설계사별 확정 지급 순액과, 확정일부터 31일 사이에 연결 계좌에서 나간 출금을 맞춰 결과를 남긴다.")
    @PostMapping("/bank-reconciliations")
    @PreAuthorize(Roles.CAN_PROCESS)
    public ApiResponse<BankReconciliationResponse> run(@Valid @RequestBody BankReconciliationRequest request,
                                                       @AuthenticationPrincipal FgcUserDetails principal) {
        return ApiResponse.success(reconciliationService.run(principal.getUserId(), request.settlementMonth()));
    }

    @Operation(summary = "지급 이체 대사 결과", description = "그 정산월에 마지막으로 돌린 대사 결과. 돌린 적이 없으면 data 가 null 이다.",
            responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK",
                    content = @Content(schema = @Schema(implementation = LatestBankReconciliationEnvelope.class))))
    @GetMapping("/bank-reconciliations")
    @PreAuthorize(Roles.ANY_ROLE)
    public ApiResponse<BankReconciliationResponse> latest(
            @Parameter(description = "정산월(YYYY-MM)", example = "2026-07") @RequestParam String month) {
        return ApiResponse.success(reconciliationService.latest(month));
    }

    /** IF-API-99 조회 전용 응답 스키마. 실행 성공 응답(IF-API-98)은 항상 결과가 있다. */
    record LatestBankReconciliationEnvelope(BankReconciliationResponse data, ApiError error, String requestId) {}
}
