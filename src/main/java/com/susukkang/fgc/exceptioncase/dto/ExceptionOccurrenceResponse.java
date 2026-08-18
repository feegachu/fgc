package com.susukkang.fgc.exceptioncase.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.common.util.DateUtil;

import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 관리자에게 보여 줄 월 통합검증 실행별 검출 증거. */
public record ExceptionOccurrenceResponse(
        Long exceptionOccurrenceId,
        Long validationRunId,
        Integer runNo,
        LocalDate validationMonth,
        String exceptionType,
        String reasonCode,
        String sourceEntityType,
        String sourceEntityId,
        String evidenceJson,
        boolean newCase,
        boolean reopened,
        OffsetDateTime detectedAt
) {
    private static final ObjectMapper EVIDENCE_MAPPER = new ObjectMapper();

    /**
     * jsonb_build_object 가 만드는 증거 키 → 관리자 표기. 여기 없는 키(내부 ID·스냅샷
     * 원문·이관 메타)는 화면에 풀지 않는다 — 전체 증거는 원천 행과 evidence_snapshot 이 보존한다.
     */
    private static final Map<String, String> EVIDENCE_LABELS = evidenceLabels();

    private static Map<String, String> evidenceLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("paymentStage", "지급단계");
        labels.put("resultType", "결과유형");   // 대사(reconciliation_result.result_type)
        labels.put("resultStatus", "결과상태"); // CAP·차익거래(cap_check/arbitrage_check.result_status)
        labels.put("primaryReasonCode", "주원인");
        labels.put("limitAmount", "한도액");
        labels.put("includedAmount", "산입액");
        labels.put("remainingAmount", "잔여액");
        labels.put("usagePct", "사용률");
        labels.put("expectedTotalAmount", "예상금액");
        labels.put("actualTotalAmount", "실제금액");
        labels.put("differenceAmount", "차액");
        labels.put("netDifferenceAmount", "순차액");
        labels.put("asOfDate", "기준일");
        labels.put("journalNo", "분개번호");
        labels.put("journalType", "분개유형");
        labels.put("debitTotal", "차변합계");
        labels.put("creditTotal", "대변합계");
        return labels;
    }

    /** 원인·결과 코드는 상세 원인과 같은 한글 라벨로 푼다. */
    private static final Set<String> CODE_KEYS = Set.of("resultType", "primaryReasonCode");

    public static ExceptionOccurrenceResponse from(ExceptionOccurrenceRow row) {
        return new ExceptionOccurrenceResponse(
                row.exceptionOccurrenceId(), row.validationRunId(), row.runNo(),
                row.validationMonth(), row.exceptionType(), row.reasonCode(),
                row.sourceEntityType(), row.sourceEntityId(), row.evidenceJson(),
                row.newCase(), row.reopened(), DateUtil.toSeoul(row.detectedAt()));
    }

    public String detectionLabel() {
        if (reopened) return "재발·재개";
        return newCase ? "신규" : "재검출";
    }

    /** 검출 증거의 표시 항목 한 줄. */
    public record EvidenceItem(String label, String value) {
    }

    /**
     * 증거 스냅샷을 원시 JSON 대신 관리자가 읽는 항목 목록으로 푼다.
     * 파싱이 안 되거나 알려진 키가 없으면 빈 목록 — 검출 이력 자체는 그대로 보인다.
     */
    public List<EvidenceItem> evidenceItems() {
        if (evidenceJson == null || evidenceJson.isBlank()) {
            return List.of();
        }
        JsonNode evidence;
        try {
            evidence = EVIDENCE_MAPPER.readTree(evidenceJson);
        } catch (JsonProcessingException unreadable) {
            return List.of();
        }
        DecimalFormat amountFormat = new DecimalFormat("#,##0.##");
        List<EvidenceItem> items = new ArrayList<>();
        EVIDENCE_LABELS.forEach((key, label) -> {
            JsonNode value = evidence.get(key);
            if (value == null || value.isNull() || value.isContainerNode()) {
                return;
            }
            items.add(new EvidenceItem(label, formatValue(key, value, amountFormat)));
        });
        return items;
    }

    private static String formatValue(String key, JsonNode value, DecimalFormat amountFormat) {
        if (CODE_KEYS.contains(key)) {
            return ExceptionCaseResponseDTO.labelOf(value.asText());
        }
        if ("usagePct".equals(key)) {
            return value.asText() + "%";
        }
        if (value.isNumber() && (key.endsWith("Amount") || key.endsWith("Total"))) {
            return amountFormat.format(value.decimalValue()) + "원";
        }
        return value.asText();
    }
}
