package com.susukkang.fgc.clawback.controller;

import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.clawback.dto.ClawbackNoticeDocumentRequest;
import com.susukkang.fgc.clawback.dto.ClawbackNoticeDocumentResponse;
import com.susukkang.fgc.clawback.service.ClawbackNoticeDocumentService;
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
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/** IF-API-73 환수 통지서 PDF 발행·재발행과 IF-API-86 발행 이력. CLAW-W01 내부 기능이며 AGENT에는 열지 않는다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/clawbacks/{id}/notice-documents")
@PreAuthorize(Roles.ANY_ROLE)
public class ClawbackNoticeDocumentController {
    private final ClawbackNoticeDocumentService service;

    @GetMapping
    @Operation(operationId = "listClawbackNoticeDocuments", summary = "IF-API-86 환수 통지서 발행 이력")
    public ApiResponse<PageResponse<ClawbackNoticeDocumentResponse>> history(
            @PathVariable long id,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.history(id, page, size));
    }

    @PostMapping
    @PreAuthorize(Roles.CAN_CALCULATE_CLAWBACK)
    @Operation(operationId = "issueClawbackNoticeDocument", summary = "IF-API-73 환수 통지서 PDF 발행",
            description = "지정한 계산판 snapshot으로 새 판을 만듭니다. 같은 Idempotency-Key 재요청은 같은 판을 반환하고, "
                    + "환수 상태·통지일·확정액은 바꾸지 않습니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201", description = "발행한 문서 판", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "계산판·원천 버전 변경 또는 멱등키 충돌")
    })
    public ResponseEntity<ApiResponse<ClawbackNoticeDocumentResponse>> issue(
            @PathVariable long id,
            @Valid @RequestBody ClawbackNoticeDocumentRequest request,
            @AuthenticationPrincipal FgcUserDetails principal,
            @Parameter(description = "발행 요청의 멱등키", required = true)
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        var document = service.issue(id, request, idempotencyKey, principal.getUserId());
        return ResponseEntity.created(URI.create(document.downloadPath())).body(ApiResponse.success(document));
    }
}
