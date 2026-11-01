package com.susukkang.fgc.clawback.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static com.susukkang.fgc.clawback.dto.ClawbackCandidateSearchCondition.*;

/** 환수 후보 목록행. 자료 없음과 실제 0, 미계산을 구분하기 위해 null을 그대로 반환한다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ClawbackCandidateResponse(
        Long candidateId, Long contractId, String contractNo,
        Long insurerId, String insurerName, Long productOfferingId, String productName,
        Long agentId, String agentName, TriggerType triggerType, Long sourceId,
        LocalDate eventDate, String dateBasisLabel, LocalDate contractDate, String sourceVersion,
        CalculationStatus calculationStatus, List<String> reviewReasons,
        @Schema(nullable = true) BigDecimal paidAmount, @Schema(nullable = true) BigDecimal unpaidAmount,
        @Schema(nullable = true) Long policyVersionId, @Schema(nullable = true) Long clawbackCaseId,
        @Schema(nullable = true) BigDecimal calculatedAmount, List<String> allowedActions
) { }
