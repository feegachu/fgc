package com.susukkang.fgc.validation.controller;

import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.common.web.RequestIdContext;
import com.susukkang.fgc.validation.dto.CreateValidationRunCommand;
import com.susukkang.fgc.validation.dto.CreateValidationRunRequest;
import com.susukkang.fgc.validation.dto.CreateValidationRunResponse;
import com.susukkang.fgc.validation.dto.FinalizeChecklistResponse;
import com.susukkang.fgc.validation.dto.FinalizeValidationRunResponse;
import com.susukkang.fgc.validation.dto.ValidationRunDetailResponse;
import com.susukkang.fgc.validation.dto.ValidationRunExecuteResponse;
import com.susukkang.fgc.validation.dto.ValidationRunListRow;
import com.susukkang.fgc.validation.dto.ValidationRunProgressResponse;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.dto.ValidationRunSearchCriteria;
import com.susukkang.fgc.validation.dto.ValidationRunSearchResponse;
import com.susukkang.fgc.validation.service.ValidationRunCreateService;
import com.susukkang.fgc.validation.service.ValidationRunDetailService;
import com.susukkang.fgc.validation.service.ValidationRunExecuteService;
import com.susukkang.fgc.validation.service.ValidationRunFinalizationService;
import com.susukkang.fgc.validation.service.ValidationRunSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Map;

/**
 * FGC-FUN-041 검증 실행 생성/목록 조회 API
 */
@Tag(name = "검증 실행", description = "월 통합검증·수동검증 실행 생성/조회 API")
@RestController
@RequestMapping("/api/v1/validation-runs")
@RequiredArgsConstructor
public class ValidationRunController {

    private final ValidationRunCreateService validationRunCreateService;
    private final ValidationRunSearchService validationRunSearchService;
    private final ValidationRunDetailService validationRunDetailService;
    private final ValidationRunExecuteService validationRunExecuteService;
    private final ValidationRunFinalizationService validationRunFinalizationService;

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
                    responseCode = "403", description = "허용 역할(SETTLEMENT·SYSTEM_ADMIN) 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "동일 월 활성 MONTHLY 실행 중복 (FGC-VRUN-001)")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.CAN_PROCESS)
    public ApiResponse<CreateValidationRunResponse> create(
            @RequestBody CreateValidationRunRequest request,
            @AuthenticationPrincipal FgcUserDetails principal
    ) {
        // 파싱 실패를 FgcBusinessException 바꿔서 GlobalExceptionHandler가 400으로 처리하게 함
        LocalDate validationMonth;
        try {
            validationMonth = DateUtil.parseSettlementMonth(request.validationMonth());
        } catch (DateTimeException | NullPointerException e) {
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

    @Operation(
            summary = "월 통합검증 실행 목록 조회",
            description = "검증월·상태로 검색하고, 실행/확정/실패/진행 단계 정보를 페이징된 목록으로 돌려준다. "
                    + "인증된 전체 사용자(SYSTEM_ADMIN, GA_ADMIN, SETTLEMENT, COMPLIANCE)가 조회할 수 있다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = ValidationRunSearchResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "page<1, size가 1~100 범위 밖, month 형식(yyyy-MM) 오류, "
                            + "status 값 오류 등 (FGC-COMMON-002)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "인증되지 않은 요청")
    })
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ValidationRunSearchResponse> search(
            @Parameter(description = "검증월(yyyy-MM)", example = "2026-08")
            @RequestParam(required = false) String month,
            @Parameter(description = "실행 상태(CREATED/RUNNING/COMPLETED/FAILED/FINALIZED)")
            @RequestParam(required = false) String status,
            @Parameter(description = "페이지(1-base)") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "페이지 크기(최대 100)") @RequestParam(defaultValue = "20") int size
    ) {
        // 1) month 파싱
        LocalDate validationMonth = null;
        if(month != null){
            try {
                validationMonth = DateUtil.parseSettlementMonth(month);
            } catch (DateTimeException | NullPointerException e){
                throw new FgcBusinessException(FgcErrorCode.COMMON_002, "month",
                        Map.of("field", "month"), null);
            }
        }

        // 2) status 검증
        if (status != null){
            try {
                ValidationRunStatus.valueOf(status);
            } catch (IllegalArgumentException e){
                throw new FgcBusinessException(FgcErrorCode.COMMON_002, "status",
                        Map.of("field", "status"), null);
            }
        }

        // 3) criteria 조립 + 서비스 호출
        ValidationRunSearchCriteria criteria = new ValidationRunSearchCriteria(validationMonth, status);
        PageResponse<ValidationRunListRow> pageResponse = validationRunSearchService.search(criteria, page, size);

        // 4) 응답 변환
        return ApiResponse.success(ValidationRunSearchResponse.from(pageResponse));
    }

    @Operation(
            summary = "검증 실행 상세 조회 (IF-API-47)",
            description = "실행 헤더 + 대상 선별 결과(validation_target) + 결과 요약 4블록(1,200%·차익거래·원장·대사). "
                    + "요약은 결과 테이블을 매번 집계한다 — 화면용 복제 저장 없음(FUN-043)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = ValidationRunDetailResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "인증되지 않은 요청"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "실행 미존재 (FGC-COMMON-004)")
    })
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ValidationRunDetailResponse> detail(
            @Parameter(description = "validation_run_id") @PathVariable Long id
    ) {
        return ApiResponse.success(validationRunDetailService.detail(id));
    }

    @Operation(
            summary = "검증 실행 기동 (IF-API-48)",
            description = "CREATED 상태의 실행을 MonthlyValidationJob(IF-BAT-01)으로 비동기 기동한다. "
                    + "202는 수락의 의미이며 실제 진행은 IF-API-49 폴링으로 본다. "
                    + "1차는 재기동을 지원하지 않는다 — FAILED면 새 실행을 만든다(FUN-045는 2차)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "202", description = "기동 수락",
                    content = @Content(schema = @Schema(implementation = ValidationRunExecuteResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "인증되지 않은 요청"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "허용 역할(SETTLEMENT·SYSTEM_ADMIN) 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "실행 미존재 (FGC-COMMON-004)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "FINALIZED (FGC-VRUN-003) 또는 CREATED가 아닌 상태 (FGC-VRUN-004)")
    })
    @PostMapping("/{id}/execute")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize(Roles.CAN_PROCESS)
    public ApiResponse<ValidationRunExecuteResponse> execute(
            @Parameter(description = "validation_run_id") @PathVariable Long id,
            @AuthenticationPrincipal FgcUserDetails principal
    ) {
        ValidationRunRow row = validationRunExecuteService.execute(
                id, principal.getUserId(), RequestIdContext.current());
        return ApiResponse.success(ValidationRunExecuteResponse.from(row));
    }

    @Operation(
            summary = "검증 실행 진행률 조회 (IF-API-49)",
            description = "validation_run.current_step 기반 진행률 — 화면이 2초 간격으로 폴링한다. "
                    + "progressPct = current_step / 10 × 100 (운영정책서 제43조)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = ValidationRunProgressResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "인증되지 않은 요청"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "실행 미존재 (FGC-COMMON-004)")
    })
    @GetMapping("/{id}/progress")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ValidationRunProgressResponse> progress(
            @Parameter(description = "validation_run_id") @PathVariable Long id
    ) {
        return ApiResponse.success(validationRunDetailService.progress(id));
    }

    @Operation(
            summary = "검증 실행 확정 체크리스트 조회 (IF-API-50)",
            description = "운영정책서 제44조의 확정 전 필수조건 6개를 문서 순서와 문구 그대로 반환한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = FinalizeChecklistResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "인증되지 않은 요청"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "검증 실행 없음 (FGC-COMMON-004)")
    })
    @GetMapping("/{id}/finalize-checklist")
    @PreAuthorize(Roles.ANY_ROLE)
    public ApiResponse<FinalizeChecklistResponse> getFinalizeChecklist(
            @PathVariable("id") Long validationRunId
    ) {
        return ApiResponse.success(validationRunFinalizationService.getChecklist(validationRunId));
    }

    @Operation(
            summary = "검증 실행 확정 (IF-API-51)",
            description = "6개 조건을 서버에서 재검사한 뒤 COMPLETED/8 실행을 FINALIZED/10으로 잠근다. "
                    + "이는 검증 결과 잠금이며 실제 송금·법정 회계마감이 아니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "확정 성공 또는 동일 멱등키 재요청",
                    content = @Content(schema = @Schema(implementation = FinalizeValidationRunResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "Idempotency-Key 형식 오류 (FGC-COMMON-002)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "인증되지 않은 요청"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "허용 역할(GA_ADMIN·SYSTEM_ADMIN) 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "확정 결과 불변 또는 상태 경합 (FGC-VRUN-003/005)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422", description = "확정 조건 미충족 (FGC-VRUN-002)")
    })
    @PostMapping("/{id}/finalize")
    @PreAuthorize(Roles.CAN_FINALIZE_VALIDATION)
    public ApiResponse<FinalizeValidationRunResponse> finalizeRun(
            @PathVariable("id") Long validationRunId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal FgcUserDetails principal
    ) {
        return ApiResponse.success(validationRunFinalizationService.finalizeRun(
                validationRunId, principal.getUserId(), idempotencyKey));
    }
}
