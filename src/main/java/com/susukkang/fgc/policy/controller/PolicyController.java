package com.susukkang.fgc.policy.controller;

import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.code.PolicyStatus;
import com.susukkang.fgc.common.code.PolicyType;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.policy.dto.PolicyDetailResponse;
import com.susukkang.fgc.policy.dto.PolicyActionRequest;
import com.susukkang.fgc.policy.dto.PolicyCloneRequest;
import com.susukkang.fgc.policy.dto.PolicyDraft;
import com.susukkang.fgc.policy.dto.PolicyVersionResponse;
import com.susukkang.fgc.policy.dto.PolicyWriteResult;
import com.susukkang.fgc.policy.service.PolicyQueryService;
import com.susukkang.fgc.policy.service.PolicyWriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

/**
 * 설명 : POL-W01 정책·룰셋 조회 및 작성·승인 API (FGC-FUN-012·013·017).
 * 초안은 작성 서비스에서 저장하고 기존 계산 경로는 승인·활성 정책만 선택한다.
 */
@Tag(name = "정책·룰셋", description = "FGC 정책·룰셋 조회 및 작성·승인 API")
@RestController
@RequestMapping("/api/v1/policies")
public class PolicyController {

    private final PolicyQueryService policyQueryService;
    private final PolicyWriteService policyWriteService;

    public PolicyController(PolicyQueryService policyQueryService, PolicyWriteService policyWriteService) {
        this.policyQueryService = policyQueryService;
        this.policyWriteService = policyWriteService;
    }

    @Operation(
            summary = "정책 초안 생성 (IF-API-79)",
            description = "정책 헤더와 유형별 규칙을 한 트랜잭션에 저장합니다. "
                    + "버전 번호·DRAFT 상태·작성자는 서버가 결정합니다. "
                    + "Idempotency-Key로 같은 요청을 다시 보내면 기존 생성 결과를 반환합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201", description = "초안 생성 성공", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "요청 형식 또는 입력값 오류 (FGC-COMMON-002)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "정책 작성 권한 없음 (FGC-AUTH-003)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "정책 코드 또는 멱등키 충돌"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422", description = "정책 규칙의 적용 구간 충돌")
    })
    @PostMapping
    @PreAuthorize(Roles.CAN_WRITE_POLICY)
    public ResponseEntity<ApiResponse<PolicyWriteResult>> createPolicyDraft(
            @Valid @RequestBody PolicyDraft draft,
            @AuthenticationPrincipal FgcUserDetails principal,
            @Parameter(description = "생성 요청의 멱등키", required = true)
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        PolicyWriteResult result = policyWriteService.createDraft(draft, principal.getUserId(), idempotencyKey);
        return ResponseEntity.created(URI.create("/api/v1/policies/" + result.policyVersionId()))
                .body(ApiResponse.success(result));
    }

    @Operation(summary = "정책 초안 수정 (IF-API-80)",
            description = "DRAFT 정책의 헤더와 규칙을 저장합니다. policyCode는 변경할 수 없으며 "
                    + "expectedUpdatedAt으로 동시 수정을 검사합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "초안 수정 성공", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "정책 상태·수정 시각 또는 멱등키 충돌")
    })
    @PutMapping("/{policyVersionId}")
    @PreAuthorize(Roles.CAN_WRITE_POLICY)
    public ApiResponse<PolicyWriteResult> updatePolicyDraft(
            @PathVariable Long policyVersionId,
            @Valid @RequestBody PolicyDraft draft,
            @AuthenticationPrincipal FgcUserDetails principal,
            @Parameter(description = "수정 요청의 멱등키", required = true)
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.success(policyWriteService.updateDraft(
                policyVersionId, draft, principal.getUserId(), idempotencyKey));
    }

    @Operation(summary = "승인 정책의 새 초안 버전 생성 (IF-API-81)",
            description = "기존 승인 정책의 규칙과 근거를 새 ID로 복사합니다. "
                    + "새 버전은 요청자를 작성자로 하는 DRAFT이며 승인 정보는 초기화합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201", description = "새 초안 버전 생성 성공", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "복제할 수 없는 정책 상태 또는 멱등키 충돌")
    })
    @PostMapping("/{policyVersionId}/versions")
    @PreAuthorize(Roles.CAN_WRITE_POLICY)
    public ResponseEntity<ApiResponse<PolicyWriteResult>> clonePolicyVersion(
            @PathVariable Long policyVersionId,
            @Valid @RequestBody PolicyCloneRequest request,
            @AuthenticationPrincipal FgcUserDetails principal,
            @Parameter(description = "복제 요청의 멱등키", required = true)
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        PolicyWriteResult result = policyWriteService.cloneVersion(
                policyVersionId, request, principal.getUserId(), idempotencyKey);
        return ResponseEntity.created(URI.create("/api/v1/policies/" + result.policyVersionId()))
                .body(ApiResponse.success(result));
    }

    @Operation(summary = "정책 제출·승인·반려·활성화 (IF-API-75)",
            description = "SUBMIT은 정책 작성 역할, APPROVE·REJECT·ACTIVATE는 GA_ADMIN·SYSTEM_ADMIN만 가능합니다. "
                    + "작성자 자기 승인과 기대 상태·수정 시각 충돌을 거절합니다. "
                    + "ACTIVATE는 같은 코드의 기존 ACTIVE를 RETIRED로 전환하고 계약일별 적용 경계를 기록합니다. "
                    + "새 적용일 전 계약은 구버전, 당일부터 체결한 계약은 변경 요율의 신버전을 선택하며 승인본의 기간·규칙은 보존합니다. "
                    + "다른 코드와 충돌하면 종료·활성화·이력·감사를 모두 롤백합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "동작 실행 성공", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "동작 수행 권한 없음 (FGC-AUTH-003)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "자기 승인·정책 상태·수정 시각 또는 멱등키 충돌"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422", description = "활성 정책의 적용기간 충돌 (FGC-POL-002)")
    })
    @PostMapping("/{policyVersionId}/actions")
    @PreAuthorize(Roles.CAN_WRITE_POLICY)
    public ApiResponse<PolicyWriteResult> executePolicyAction(
            @PathVariable Long policyVersionId,
            @Valid @RequestBody PolicyActionRequest request,
            @AuthenticationPrincipal FgcUserDetails principal,
            @Parameter(description = "동작 요청의 멱등키", required = true)
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.success(policyWriteService.executeAction(
                policyVersionId, request, principal.getUserId(), idempotencyKey));
    }

    @Operation(
            summary = "정책 버전 목록 조회 (IF-API-09)",
            description = "정책 유형·기준일·상태로 정책 버전 목록을 조회합니다. "
                    + "기준일(asOf)은 해당 일자에 유효한 버전만 남깁니다 — 정책은 계약일 기준으로 고르는 경우가 많습니다. "
                    + "조건에 맞는 자료가 없으면 200과 빈 배열을 반환합니다."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<PolicyVersionResponse>> findPolicyVersions(
            @Parameter(description = "정책 유형", example = "CAP_1200")
            @RequestParam(required = false)
            PolicyType type,
            @Parameter(description = "기준일(yyyy-MM-dd) — 해당 일자에 유효한 버전만 조회", example = "2026-07-01")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate asOf,
            @Parameter(description = "정책 상태", example = "ACTIVE")
            @RequestParam(required = false)
            PolicyStatus status
    ) {
        return ApiResponse.success(policyQueryService.findPolicyVersions(type, asOf, status));
    }

    @Operation(
            summary = "정책 버전 상세 조회 (IF-API-10)",
            description = "정책 버전 1건의 상세를 POL-W01 탭 구성(수수료 규칙, 1,200% 룰셋·항목별 산입 판정, "
                    + "예상 해약환급률표, 근거 참조)으로 조회합니다. 탭 클릭 시 Ajax 로 호출됩니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "조회 성공",
                    useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "정책 버전 없음 (FGC-COMMON-004)")
    })
    @GetMapping("/{policyVersionId}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<PolicyDetailResponse> findPolicyDetail(
            @Parameter(description = "정책 버전 ID", example = "1")
            @PathVariable Long policyVersionId
    ) {
        return ApiResponse.success(policyQueryService.findPolicyDetail(policyVersionId));
    }
}
