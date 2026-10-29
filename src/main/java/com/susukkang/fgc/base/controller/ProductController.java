package com.susukkang.fgc.base.controller;

import com.susukkang.fgc.base.dto.ProductResponse;
import com.susukkang.fgc.base.dto.ProductSearchCriteria;
import com.susukkang.fgc.base.service.ProductService;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@Tag(name = "기준정보", description = "FGC 기준정보 조회 API")
@RestController
@RequestMapping("/api/v1/base/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @Operation(
            summary = "보험상품 기준정보 조회·선택",
            description = "보험회사와 기준일자에 해당하는 활성 상품 판매버전을 조회합니다. "
                    + "includeExpired=true이면 판매 종료 버전을 포함하고, includeHistorical=true이면 판매기간·활성 조건을 해제해 계약 검색용 전체 버전을 반환합니다."
    )
    @GetMapping
    @PreAuthorize(Roles.CAN_VIEW_PUBLIC_PRODUCTS)
    public ApiResponse<PageResponse<ProductResponse>> search(
            @Parameter(description = "상품명·상품코드·판매버전 부분 검색") @RequestParam(required = false) String keyword,
            @Parameter(description = "기존 선택 판매버전 ID 재조회. 기준일·보험회사 조건은 유지") @RequestParam(required = false) Long selectedId,
            @Parameter(description = "보험회사 ID", example = "2", required = true)
            @RequestParam
            long insurerId,
            @Parameter(description = "기준일자(yyyy-MM-dd)", example = "2026-08-11", required = true)
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate asOf,
            @Parameter(description = "판매 종료 버전 포함 여부(계약 이력 검색용, 판매시작일은 기준일 이하로 유지)")
            @RequestParam(defaultValue = "false")
            boolean includeExpired,
            @Parameter(description = "계약 목록 이력 검색 모드. 기간·활성 조건을 해제하며 입력 검증에는 사용하지 않습니다.")
            @RequestParam(defaultValue = "false")
            boolean includeHistorical,
            @Parameter(description = "정렬(key,asc|desc). insurerProductCode·productName·standardProductCode·offeringVersion·salesStartDate·basicDocumentDate. 비우면 상품코드·판매버전 순")
            @RequestParam(required = false)
            String sort,
            @Parameter(description = "페이지(1-base)", example = "1")
            @RequestParam(defaultValue = "1")
            int page,
            @Parameter(description = "페이지 크기(최대 100)", example = "20")
            @RequestParam(defaultValue = "20")
            int size
    ) {
        ProductSearchCriteria criteria = new ProductSearchCriteria(insurerId, asOf, includeExpired, includeHistorical, sort, keyword, selectedId);
        return ApiResponse.success(productService.search(criteria, page, size));
    }
}
