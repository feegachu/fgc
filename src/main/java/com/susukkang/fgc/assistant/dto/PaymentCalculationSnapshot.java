package com.susukkang.fgc.assistant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Read-only result. Null validity intentionally grants no recommendation or guide proof. */
public record PaymentCalculationSnapshot(
        String calculationId, String kind, String mode, String status, long clientRevision,
        boolean synthetic, boolean snapshotOnly, String evidenceKind, String currency, String paymentStatus,
        Map<String, String> resolvedScope, Map<String, String> amounts,
        List<Map<String, Object>> capPreview, Boolean confirmable, List<Reason> blockers,
        List<String> missingInputs, Basis basis, Recommendation recommendation,
        OffsetDateTime calculatedAt,
        Validity validity, List<CalculationLine> lines) {
    @Schema(name="AssistantCalculationLine")
    public record CalculationLine(String sourceId, String contractId, String commissionItemId, String beneficiaryAgentId, Integer installmentNo, String attributionDate, String dueDate, String basisCode, String basisAmount, String calculationType, String ratePct, String expectedAmount, String ruleId, String status) {}
    public PaymentCalculationSnapshot(String calculationId, String kind, String mode, String status, long clientRevision,
            boolean synthetic, boolean snapshotOnly, String evidenceKind, String currency, String paymentStatus,
            Map<String,String> resolvedScope, Map<String,String> amounts, List<Map<String,Object>> capPreview,
            Boolean confirmable, List<Reason> blockers, List<String> missingInputs, Basis basis,
            Recommendation recommendation, OffsetDateTime calculatedAt, Validity validity) {
        this(calculationId,kind,mode,status,clientRevision,synthetic,snapshotOnly,evidenceKind,currency,paymentStatus,
                resolvedScope,amounts,capPreview,confirmable,blockers,missingInputs,basis,recommendation,calculatedAt,validity,List.of());
    }
    @Schema(name="AssistantCalculationValidity")
    public record Validity(String stateToken, OffsetDateTime calculatedAt, OffsetDateTime expiresAt,
                           String inputFingerprint,String dependencyFingerprint) {}
    public record Reason(String code, String message, String contractId, String transactionAttributionId) {}
    @Schema(name = "AssistantCalculationSource")
    public record Source(String sourceId, String sourceType, String title, String version, String entityId) {}
    public record Basis(List<Source> sourceRefs, Rounding rounding, String asOfDate,
            List<String> policyVersionIds, List<String> ruleIds, List<AttributionLine> lines) {}
    @Schema(name="AssistantAttributionLine")
    public record AttributionLine(String attributionSequence, String contractId, String attributionDate,
            String originalAmount, String roundedAmount) {}
    public record Rounding(int scale, String mode, String aggregation) {}
    public record Recommendation(boolean allowed, String targetId, String suggestedAmount, String reason, List<String> requiredChecks) {}
}
