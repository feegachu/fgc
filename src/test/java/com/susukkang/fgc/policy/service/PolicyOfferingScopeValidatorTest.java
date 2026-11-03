package com.susukkang.fgc.policy.service;

import com.susukkang.fgc.base.entity.Product;
import com.susukkang.fgc.base.entity.ProductOffering;
import com.susukkang.fgc.base.repository.ProductOfferingRepository;
import com.susukkang.fgc.base.repository.ProductRepository;
import com.susukkang.fgc.common.code.PolicySourceClass;
import com.susukkang.fgc.common.code.PolicyType;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.policy.dto.PolicyDraft;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@DisplayName("FGC-FUN-017 환수 규칙의 판매버전·보험회사 제출 검증")
@ExtendWith(MockitoExtension.class)
class PolicyOfferingScopeValidatorTest {

    @Mock
    private ProductOfferingRepository offeringRepository;
    @Mock
    private ProductRepository productRepository;
    @InjectMocks
    private PolicyOfferingScopeValidator validator;

    @Test
    void rejectsMissingClawbackOfferingWithItsField() {
        given(offeringRepository.findById(10L)).willReturn(Optional.empty());

        assertInvalid(draft(10L, null), "clawbackRules[0].productOfferingId");
    }

    @Test
    void rejectsClawbackOfferingFromAnotherInsurerWithItsField() {
        givenOfferingWithInsurer(1L);

        assertInvalid(draft(10L, 2L), "clawbackRules[0].insurerId");
    }

    @Test
    void acceptsClawbackOfferingWithMatchingInsurer() {
        givenOfferingWithInsurer(1L);

        assertThatCode(() -> validator.validateForSubmission(draft(10L, 1L)))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsClawbackOfferingWithoutExplicitInsurer() {
        given(offeringRepository.findById(10L)).willReturn(Optional.of(new ProductOffering()));

        assertThatCode(() -> validator.validateForSubmission(draft(10L, null)))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsClawbackRuleWithoutOfferingRestriction() {
        assertThatCode(() -> validator.validateForSubmission(draft(null, 1L)))
                .doesNotThrowAnyException();
    }

    private void givenOfferingWithInsurer(Long insurerId) {
        ProductOffering offering = mock(ProductOffering.class);
        Product product = mock(Product.class);
        given(offeringRepository.findById(10L)).willReturn(Optional.of(offering));
        given(offering.getProductId()).willReturn(20L);
        given(productRepository.findById(20L)).willReturn(Optional.of(product));
        given(product.getInsurerId()).willReturn(insurerId);
    }

    private void assertInvalid(PolicyDraft draft, String field) {
        assertThatThrownBy(() -> validator.validateForSubmission(draft))
                .isInstanceOfSatisfying(FgcBusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.COMMON_002);
                    assertThat(exception.getField()).isEqualTo(field);
                    assertThat(exception.getParams()).containsEntry("field", field);
                });
    }

    private static PolicyDraft draft(Long offeringId, Long insurerId) {
        var rule = new PolicyDraft.ClawbackRuleDraft(null, insurerId, offeringId, null,
                0, 11, "PAID_ATTRIBUTED_AMOUNT", new BigDecimal("50"), null);
        return new PolicyDraft("CLAWBACK-SCOPE-TEST", "환수 범위 검증", PolicyType.CLAWBACK,
                PolicySourceClass.GA_POLICY, LocalDate.of(2026, 1, 1), null, null, null,
                List.of(), List.of("D-02"), List.of(), List.of(), List.of(), List.of(), List.of(rule), null);
    }
}
