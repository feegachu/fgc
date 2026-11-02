package com.susukkang.fgc.contract.controller;

import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.common.web.CsvExportWriter;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.contract.dto.*;
import com.susukkang.fgc.contract.service.ContractService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 설명 : ContractController
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-05
 */
@RestController
@RequestMapping("/api/v1/contracts")
@RequiredArgsConstructor
public class ContractController {
    private final ContractService contractService;

    /**
     * 설명 : 검색 조건에 따라 보험계약 목록을 조회한다.
     *
     * @param condition 보험계약 검색 조건
     * @return 검색 조건에 해당하는 보험계약 목록
     * @author hjKang
     * @since 2026-08-05
     */
    @GetMapping
    public ApiResponse<PageResponse<ContractView>> getContractListByCondition(
            @ModelAttribute @Valid ContractSearchCondition condition,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(contractService.selectByCondition(condition, page, size));
    }

    /** CONT-W01 검색 결과 전체를 CSV로 내려받는다. */
    @GetMapping(value = "/export.csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportContracts(@ModelAttribute @Valid ContractSearchCondition condition) {
        List<List<?>> rows = new ArrayList<>();
        for (ContractView contract : contractService.selectAllByCondition(condition)) {
            rows.add(CsvExportWriter.row(
                    contract.getContractNo(), contract.getInsurerName(), contract.getProductName(),
                    contract.getContractDate(), contract.getMonthlyEquivalentFirstPremium(), contract.getAgentIdName(),
                    contract.getContractStatus() == null ? null : contract.getContractStatus().label(),
                    contract.getCapResultStatus() == null ? null : contract.getCapResultStatus().label(),
                    contract.getDataOrigin()
            ));
        }
        return csvAttachment("보험계약목록", List.of(
                "계약번호", "보험회사", "상품명", "계약일", "월납환산 초회보험료", "모집 설계사", "계약상태", "1,200% 판정", "데이터 출처"
        ), rows);
    }

    private static ResponseEntity<byte[]> csvAttachment(String prefix, List<String> headers, List<List<?>> rows) {
        String filename = prefix + "_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(CsvExportWriter.write(headers, rows));
    }

    /**
     * 설명 : 보험계약을 신규 등록한다.
     *
     * @param request 보험계약 등록 요청 정보
     * @return 등록된 보험계약 정보
     * @author hjKang
     * @since 2026-08-05
     */
    @PostMapping
    @PreAuthorize(Roles.CAN_PROCESS)
    public ApiResponse<ContractCreateResponse> createContract(@Valid @RequestBody ContractCreateRequest request ) {
        return ApiResponse.success(contractService.createContract(request));
    }
    /**
     * 설명 : 보험계약을 계약 수정
     *  계약 조회 -> 계약 상세보기 -> 계약 수정 -> 입력값 입력후 수정
     * @param id,request 수정할 ID + 수정할 정보
     * @return 계약 수정 응답 정보
     * @author hjKang
     * @since 2026-08-05
     */
    @Operation(summary = "보험계약 수정·상태 변경 (IF-API-19)",
            description = "expectedUpdatedAt으로 동시 수정을 검사합니다. 상태를 바꾸면 statusEvent와 Idempotency-Key가 "
                    + "필요하며 사건·후속 작업을 같은 트랜잭션에 남깁니다. 200은 저장 성공이며 후속 처리 완료가 아닙니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "수정 저장 성공", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "수정 시각 충돌·허용되지 않은 상태전이·멱등키 충돌·같은 요청 처리 중"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422", description = "최초 설계사 변경 요청 또는 허용 범위 밖의 사건 일자")
    })
    @PutMapping("/{id}")
    @PreAuthorize(Roles.CAN_PROCESS)
    public ApiResponse<ContractUpdateResponse> updateContract(
            @PathVariable Long id,
            @Valid @RequestBody ContractUpdateRequest request,
            @AuthenticationPrincipal FgcUserDetails principal,
            @Parameter(description = "요청 UUID. 상태 변경 시 필수이며 같은 키 재요청은 처음 결과를 돌려줍니다")
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.success(contractService.updateContract(id, request, idempotencyKey, principal.getUserId()));
    }
}
