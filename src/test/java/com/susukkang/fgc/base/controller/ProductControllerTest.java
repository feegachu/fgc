package com.susukkang.fgc.base.controller;

import com.susukkang.fgc.base.dto.ProductResponse;
import com.susukkang.fgc.base.dto.ProductSearchCriteria;
import com.susukkang.fgc.base.service.ProductService;
import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import com.susukkang.fgc.common.web.PageResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
@Import({ProductController.class, GlobalExceptionHandler.class, SecurityConfig.class})
class ProductControllerTest {

    private static final String SORT =
            "insurerProductCode,asc;offeringVersion,asc;channelCode,asc;productOfferingId,asc";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private FgcMessageResolver messageResolver;

    @MockitoBean
    private ConstraintErrorCodeResolver constraintErrorCodeResolver;

    @Test
    void returnsPagedProductOfferingsWithDefaultPaging() throws Exception {
        LocalDate asOf = LocalDate.of(2026, 8, 11);
        ProductSearchCriteria criteria = new ProductSearchCriteria(2L, asOf);
        ProductResponse product = new ProductResponse(
                21L,
                "P-B-001",
                "STD-LIFE-B",
                "가상 저해지 건강보험 B",
                "HEALTH_PROTECTION",
                "2026-CURRENT-B",
                LocalDate.of(2026, 7, 1),
                null,
                "BD-2026-07",
                LocalDate.of(2026, 7, 1),
                "FACE_TO_FACE",
                false,
                "CURRENT",
                true
        );
        given(productService.search(criteria, 1, 20)).willReturn(
                PageResponse.of(List.of(product), 1, 20, 1, SORT)
        );

        mockMvc.perform(get("/api/v1/base/products")
                        .param("insurerId", "2")
                        .param("asOf", "2026-08-11")
                        .with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].productOfferingId").value(21))
                .andExpect(jsonPath("$.data.content[0].insurerProductCode").value("P-B-001"))
                .andExpect(jsonPath("$.data.content[0].standardProductCode").value("STD-LIFE-B"))
                .andExpect(jsonPath("$.data.content[0].productName").value("가상 저해지 건강보험 B"))
                .andExpect(jsonPath("$.data.content[0].productGroupCode").value("HEALTH_PROTECTION"))
                .andExpect(jsonPath("$.data.content[0].offeringVersion").value("2026-CURRENT-B"))
                .andExpect(jsonPath("$.data.content[0].salesStartDate").value("2026-07-01"))
                .andExpect(jsonPath("$.data.content[0].salesEndDate").isEmpty())
                .andExpect(jsonPath("$.data.content[0].basicDocumentVersion").value("BD-2026-07"))
                .andExpect(jsonPath("$.data.content[0].basicDocumentDate").value("2026-07-01"))
                .andExpect(jsonPath("$.data.content[0].channelCode").value("FACE_TO_FACE"))
                .andExpect(jsonPath("$.data.content[0].channelSpecialRuleYn").value(false))
                .andExpect(jsonPath("$.data.content[0].feeRegimeCode").value("CURRENT"))
                .andExpect(jsonPath("$.data.content[0].standardDeduction80Yn").value(true))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.sort").value(SORT))
                .andExpect(jsonPath("$.error").isEmpty())
                .andExpect(jsonPath("$.requestId", matchesPattern("^\\d{8}-[0-9a-f]{6}$")));

        verify(productService).search(criteria, 1, 20);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "GA_ADMIN", "SETTLEMENT", "COMPLIANCE"})
    void allowsEveryAuthenticatedRole(String role) throws Exception {
        LocalDate asOf = LocalDate.of(2026, 8, 11);
        ProductSearchCriteria criteria = new ProductSearchCriteria(2L, asOf);
        given(productService.search(criteria, 2, 10)).willReturn(
                PageResponse.of(List.of(), 2, 10, 0, SORT)
        );

        mockMvc.perform(get("/api/v1/base/products")
                        .param("insurerId", "2")
                        .param("asOf", "2026-08-11")
                        .param("page", "2")
                        .param("size", "10")
                        .with(user("fgc-user").roles(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty());
    }

    @Test
    void rejectsAuthenticatedUserWithoutAllowedRole() throws Exception {
        mockMvc.perform(get("/api/v1/base/products")
                        .param("insurerId", "2")
                        .param("asOf", "2026-08-11")
                        .with(user("other-user").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @CsvSource({
            "insurerId, 2026-08-11, insurerId",
            "2, 2026/08/11, asOf"
    })
    void rejectsInvalidRequiredParameter(String insurerId, String asOf, String field) throws Exception {
        mockMvc.perform(get("/api/v1/base/products")
                        .param("insurerId", insurerId)
                        .param("asOf", asOf)
                        .with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"))
                .andExpect(jsonPath("$.error.field").value(field));
    }

    @ParameterizedTest
    @ValueSource(strings = {"insurerId", "asOf"})
    void rejectsMissingRequiredParameter(String missingField) throws Exception {
        var request = get("/api/v1/base/products")
                .with(user("settle01").roles("SETTLEMENT"));
        if (!"insurerId".equals(missingField)) {
            request.param("insurerId", "2");
        }
        if (!"asOf".equals(missingField)) {
            request.param("asOf", "2026-08-11");
        }

        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"))
                .andExpect(jsonPath("$.error.field").value(missingField));
    }

    @ParameterizedTest
    @CsvSource({"0, 1, 20, insurerId", "2, 0, 20, page", "2, 1, 101, size"})
    void rejectsInvalidSearchValues(long insurerId, int page, int size, String field) throws Exception {
        LocalDate asOf = LocalDate.of(2026, 8, 11);
        ProductSearchCriteria criteria = new ProductSearchCriteria(insurerId, asOf);
        given(productService.search(criteria, page, size)).willThrow(
                new FgcBusinessException(
                        FgcErrorCode.COMMON_002,
                        field,
                        Map.of("field", field),
                        null
                )
        );

        mockMvc.perform(get("/api/v1/base/products")
                        .param("insurerId", String.valueOf(insurerId))
                        .param("asOf", "2026-08-11")
                        .param("page", String.valueOf(page))
                        .param("size", String.valueOf(size))
                        .with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"))
                .andExpect(jsonPath("$.error.field").value(field));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/base/products")
                        .param("insurerId", "2")
                        .param("asOf", "2026-08-11"))
                .andExpect(status().isUnauthorized());
    }
}
