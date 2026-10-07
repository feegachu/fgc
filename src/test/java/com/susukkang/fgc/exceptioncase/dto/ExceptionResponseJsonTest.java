package com.susukkang.fgc.exceptioncase.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.susukkang.fgc.common.code.ExceptionSeverity;
import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.common.code.ExceptionType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** React 화면이 쓰는 한글 라벨·원천 링크·검출 증거가 API JSON 에 실리는지 확인한다(SIR-008). */
class ExceptionResponseJsonTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void summaryResponseCarriesTypeLabel() {
        JsonNode json = mapper.valueToTree(new ExceptionTypeSummaryResponse(ExceptionType.CAP_VIOLATION, 3));

        assertThat(json.get("type").asText()).isEqualTo("CAP_VIOLATION");
        assertThat(json.get("typeLabel").asText()).isEqualTo("1,200% 위반");
        assertThat(json.get("count").asLong()).isEqualTo(3);
    }

    @Test
    void caseResponseCarriesLabelsAndLinks() {
        ExceptionOccurrenceResponse occurrence = new ExceptionOccurrenceResponse(
                1L, 44L, 2, LocalDate.of(2026, 7, 1), "CAP_VIOLATION", "CAP_LIMIT_VIOLATION",
                "CAP_CHECK", "9", "{\"limitAmount\":1200000,\"usagePct\":\"104.17\"}", true, false,
                OffsetDateTime.parse("2026-07-10T00:00:00+09:00"));
        ExceptionActionResponse action = new ExceptionActionResponse(
                5L, 1, ExceptionStatus.NEW, ExceptionStatus.IN_REVIEW, "START_REVIEW", "검토", null,
                1L, "settle01", OffsetDateTime.parse("2026-07-10T00:00:00+09:00"));
        ExceptionCaseResponseDTO dto = new ExceptionCaseResponseDTO(
                11L, "KEY", ExceptionType.CAP_VIOLATION, "CAP_LIMIT_VIOLATION", ExceptionSeverity.HIGH,
                ExceptionStatus.NEW, "한도 초과", "설명", 3L, "C-0001", "설계사", null, null,
                "INSURANCE_CONTRACT", "3", null, null, LocalDate.of(2026, 7, 1), 44L, 44L,
                OffsetDateTime.parse("2026-07-10T00:00:00+09:00"),
                OffsetDateTime.parse("2026-07-10T00:00:00+09:00"), 1,
                OffsetDateTime.parse("2026-07-10T00:00:00+09:00"), List.of(occurrence), List.of(action));

        JsonNode json = mapper.valueToTree(dto);

        assertThat(json.get("type").asText()).isEqualTo("CAP_VIOLATION");
        assertThat(json.get("typeLabel").asText()).isEqualTo("1,200% 위반");
        assertThat(json.get("severityLabel").asText()).isEqualTo("높음");
        assertThat(json.get("statusLabel").asText()).isEqualTo("신규");
        assertThat(json.get("reasonLabel").asText()).isEqualTo("1,200% 한도 초과");
        assertThat(json.get("sourceLink").asText()).isEqualTo("/contracts/3");
        assertThat(json.get("capBasisLink").isNull()).isTrue();
        assertThat(json.at("/occurrences/0/detectionLabel").asText()).isEqualTo("신규");
        assertThat(json.at("/occurrences/0/evidenceItems/0/label").asText()).isEqualTo("한도액");
        assertThat(json.at("/occurrences/0/evidenceItems/0/value").asText()).isEqualTo("1,200,000원");
        assertThat(json.at("/actions/0/actionTypeLabel").asText()).isEqualTo("검토 시작");
        assertThat(json.at("/actions/0/fromStatusLabel").asText()).isEqualTo("신규");
        assertThat(json.at("/actions/0/toStatusLabel").asText()).isEqualTo("검토중");
    }
}
