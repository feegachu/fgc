package com.susukkang.fgc.policy.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;
import com.susukkang.fgc.common.code.AgentRankCode;
import com.susukkang.fgc.common.code.CalculationType;
import com.susukkang.fgc.common.code.FeeComponentType;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.PolicySourceClass;
import com.susukkang.fgc.common.code.PolicyType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/** IF-API-79·80 초안 입력. 자식 ID는 수정 시에만 동일 정책 소유인지 확인한 뒤 사용한다. */
@Schema(description = "정책 초안. 빈 규칙 목록을 저장할 수 있으며 제출 검증은 별도 동작이다.")
public record PolicyDraft(
        @NotBlank @Size(max = 80) String policyCode,
        @NotBlank @Size(max = 200) String policyName,
        @NotNull PolicyType policyType,
        @NotNull PolicySourceClass sourceClass,
        @NotNull LocalDate effectiveFrom,
        LocalDate effectiveTo,
        @Schema(allowableValues = {"CURRENT", "FOUR_YEAR_2027", "SEVEN_YEAR_2029"})
        @Size(max = 40) String feeRegimeCode,
        @Size(max = 80) String basicDocumentVersion,
        @NotNull List<@NotBlank String> regulationRefs,
        @NotNull List<@NotBlank String> sourceRefs,
        List<@NotNull @Valid SourceDetailDraft> sourceDetails,
        @NotNull List<@NotNull @Valid CommissionRuleDraft> commissionRules,
        @NotNull List<@NotNull @Valid FeeRegimeRuleDraft> feeRegimeRules,
        @NotNull List<@NotNull @Valid ParameterDraft> parameters,
        @Schema(description = "환수 기준은 D-02 미결이다. 현재는 빈 배열만 허용한다.")
        @NotNull List<@NotNull @Valid ClawbackRuleDraft> clawbackRules,
        @Schema(description = "수정 요청의 조회 시점 updatedAt. 최초 생성에서는 지정하지 않는다.")
        OffsetDateTime expectedUpdatedAt
) {
    public PolicyDraft {
        sourceDetails = sourceDetails == null ? List.of() : sourceDetails;
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw unknownField(name);
    }

    public record CommissionRuleDraft(
            @Positive Long commissionRuleId,
            @NotNull PaymentStage paymentStage,
            @Positive Long insurerId,
            @Positive Long productOfferingId,
            @Positive Long organizationId,
            AgentRankCode agentRankCode,
            @NotNull @Positive Long commissionItemId,
            @NotNull FeeComponentType feeComponentType,
            @NotNull @Min(1) @Max(84) Integer installmentFrom,
            @NotNull @Min(1) @Max(84) Integer installmentTo,
            @NotNull CalculationType calculationType,
            @NotBlank @Size(max = 40) String basisCode,
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            @Schema(type = "string", description = "요율(%), 소수 정밀도를 보존한 문자열")
            @DecimalMin("0") @Digits(integer = 3, fraction = 6) BigDecimal ratePct,
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            @Schema(type = "string", description = "고정금액(원), 소수 정밀도를 보존한 문자열")
            @DecimalMin("0") @Digits(integer = 13, fraction = 2) BigDecimal fixedAmount,
            boolean equalMonthlyYn,
            @Size(max = 50) String paymentConditionCode,
            @NotNull Integer priorityNo
    ) {
        @JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            throw unknownField(name);
        }
    }

    public record FeeRegimeRuleDraft(
            @Positive Long feeRegimeRulesetId,
            @NotBlank @Size(max = 40) String feeRegimeCode,
            @NotNull LocalDate salesStartDateFrom,
            LocalDate salesStartDateTo,
            @Size(max = 80) String basicDocumentVersion,
            LocalDate basicDocumentDateFrom,
            LocalDate basicDocumentDateTo,
            @NotNull @Min(1) @Max(84) Integer maxInstallments,
            @Min(1) Integer longTermMaintenanceFrom,
            @Min(1) Integer longTermMaintenanceTo,
            boolean reviewRequiredYn,
            @NotNull List<@NotBlank String> sourceRefs
    ) {
        @JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            throw unknownField(name);
        }
    }

    public record ParameterDraft(
            @NotBlank @Size(max = 80) String key,
            @NotNull JsonNode value,
            @Size(max = 500) String description
    ) {
        @JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            throw unknownField(name);
        }
    }

    public record SourceDetailDraft(
            @NotBlank String ref,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 2000) String explanation,
            @Size(max = 500) String url,
            @Schema(description = "근거 상태 태그. 현행규정·개정고시·시행세칙·FAQ·회사정책 중 복수, 기본 []")
            List<@NotBlank String> statusTags
    ) {
        public SourceDetailDraft {
            // null 원소는 남겨 List<@NotBlank String> 검증이 필드 오류(400)를 만들게 한다(List.copyOf는 null에서 NPE).
            statusTags = statusTags == null ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(statusTags)));
        }

        @JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            throw unknownField(name);
        }
    }

    public record ClawbackRuleDraft(
            @Positive Long insurerId,
            @Positive Long productOfferingId,
            @Positive Long commissionItemId,
            @NotNull @Min(0) Integer elapsedMonthFrom,
            @NotNull @Min(0) Integer elapsedMonthTo,
            @NotBlank @Size(max = 40) String clawbackBasisCode,
            @NotNull @DecimalMin("0") @DecimalMax("100")
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            @Digits(integer = 3, fraction = 6) BigDecimal clawbackRatePct,
            JsonNode conditionExpression
    ) {
        @JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            throw unknownField(name);
        }
    }

    private static IllegalArgumentException unknownField(String name) {
        return new IllegalArgumentException("허용되지 않은 정책 초안 필드입니다: " + name);
    }
}
