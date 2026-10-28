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
        @Schema(types = {"object", "null"}, description = "Always null in snapshot-only phase; not a server proof") Object validity) {
    public record Reason(String code, String message, String contractId, String transactionAttributionId) {}
    public record Source(String sourceId, String sourceType, String title, String version, String entityId) {}
    public record Basis(List<Source> sourceRefs, Rounding rounding) {}
    public record Rounding(int scale, String mode, String aggregation) {}
    public record Recommendation(boolean allowed, String targetId, String suggestedAmount, String reason, List<String> requiredChecks) {}
}
