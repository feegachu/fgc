package com.susukkang.fgc.validation.controller;

import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.validation.dto.CreateValidationRunCommand;
import com.susukkang.fgc.validation.dto.CreateValidationRunRequest;
import com.susukkang.fgc.validation.dto.CreateValidationRunResponse;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.service.ValidationRunCreateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Map;

/**
 * FGC-FUN-041 검증 실행 생성 API
 */
@Tag(name = "검증 실행", description = "월 통합검증·수동검증 실행 생성/조회 API")
@RestController
@RequestMapping("/api/v1/validation-runs")
@RequiredArgsConstructor
public class ValidationRunController {

    private final ValidationRunCreateService validationRunCreateService;

    @Operation(
            summary = "검증 실행 생성 (IF-API-45)",
            description = "검증월과 실행 유형을 받아 새 validation_run을 CREATED 상태로 만든다. "
                    + "동일 월에 활성(CREATED/RUNNING) MONTHLY 실행이 있으면 409."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201", description = "생성 성공",
                    content = @Content(schema = @Schema(implementation = CreateValidationRunResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "validationMonth 형식(yyyy-MM) 오류, runType 값 오류 등 (FGC-COMMON-002)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "인증되지 않은 요청"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "SETTLEMENT 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "동일 월 활성 MONTHLY 실행 중복 (FGC-VRUN-001)")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SETTLEMENT')")
    public ApiResponse<CreateValidationRunResponse> create(
            @RequestBody CreateValidationRunRequest request,
            @AuthenticationPrincipal FgcUserDetails principal
    ) {
        // 파싱 실패를 FgcBusinessException 바꿔서 GlobalExceptionHandler가 400으로 처리하게 함
        LocalDate validationMonth;
        try {
            validationMonth = DateUtil.parseSettlementMonth(request.validationMonth());
        } catch (DateTimeException e) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_002, "validationMonth",
                    Map.of("field", "validationMonth"), null);
        }

        ValidationRunType runType;
        try {
            runType = ValidationRunType.valueOf(request.runType());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_002, "runType",
                    Map.of("field", "runType"), null);
        }

        CreateValidationRunCommand command =
                new CreateValidationRunCommand(validationMonth, runType, principal.getUserId());

        ValidationRunRow row = validationRunCreateService.create(command);

        return ApiResponse.success(CreateValidationRunResponse.from(row));
    }
}
