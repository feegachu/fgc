package com.susukkang.fgc.clawback.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** IF-API-84: 실제 원천·귀속·운영 스케줄·후속 처리 이력과 최신 계산판. 경과월은 D-02(제48조의3) 기준이다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ClawbackCandidateDetailResponse(
        ClawbackCandidateResponse candidate, List<SourceReference> sourceRefs,
        List<PaymentEvidence> paymentAttributions, List<ScheduleEvidence> scheduleLines,
        List<PolicyCandidate> policyCandidates, List<ProcessingEffect> effects,
        @Schema(nullable = true) Integer elapsedMonths,
        @Schema(nullable = true) Calculation latestCalculation
) {
    public record SourceReference(String triggerType, Long sourceId,
                                  @Schema(nullable = true) OffsetDateTime effectiveAt,
                                  @Schema(nullable = true) LocalDate asOfDate,
                                  String status, Map<String, Object> evidence) { }

    public record PaymentEvidence(Long transactionAttributionId, Long commissionTransactionId,
                                  Long recipientAgentId, Long commissionItemId,
                                  @Schema(nullable = true) Integer installmentNo, String status, String cashflowType,
                                  @Schema(nullable = true) LocalDate paidOn, BigDecimal attributedAmount,
                                  @Schema(nullable = true) Long scheduleLineId) { }

    public record ScheduleEvidence(Long scheduleHeaderId, Long scheduleLineId, Integer scheduleVersionNo,
                                   Long beneficiaryAgentId, Long commissionItemId, Integer installmentNo,
                                   LocalDate dueDate, String lineStatus, String generationReason,
                                   BigDecimal expectedAmount, BigDecimal confirmedPaidAmount,
                                   @Schema(nullable = true) BigDecimal unpaidAmount) { }

    /** 계약일·보험회사·판매버전 범위에 맞는 정책 후보이며 D-02 산식의 승인·선택을 의미하지 않는다. */
    public record PolicyCandidate(Long policyVersionId, String policyCode, Integer versionNo,
                                  String policyName, LocalDate effectiveFrom,
                                  @Schema(nullable = true) LocalDate effectiveTo) { }

    public record ProcessingEffect(String processingJob, String processingStatus,
                                   @Schema(nullable = true) Long validationRunId, OffsetDateTime processedAt,
                                   @Schema(nullable = true) String failureReason) { }

    /** IF-API-85 계산판. 판마다 snapshot으로 보존하며 basis에 원천 버전·경과월·반올림·제외 항목을 남긴다. */
    public record Calculation(Long clawbackCaseId, Long candidateId, Integer calculationVersion,
                              String sourceVersion, Long policyVersionId, OffsetDateTime calculatedAt,
                              Long calculatedBy, BigDecimal amount, List<CalculationLine> lines, Map<String, Object> basis) { }
    public record CalculationLine(Long transactionAttributionId, Long commissionItemId, Integer installmentNo,
                                  Integer elapsedMonths, String basisCode, BigDecimal basisAmount,
                                  BigDecimal appliedRatePct, BigDecimal clawbackAmount, Long clawbackRuleId) { }
}
