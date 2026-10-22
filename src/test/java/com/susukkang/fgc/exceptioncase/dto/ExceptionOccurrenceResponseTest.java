package com.susukkang.fgc.exceptioncase.dto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** SRC-032: 검출 증거는 원시 JSON 대신 알려진 필드만 한글 라벨·콤마 금액으로 푼다. */
class ExceptionOccurrenceResponseTest {

    private ExceptionOccurrenceResponse withEvidence(String json) {
        return new ExceptionOccurrenceResponse(1L, 2L, 3, null, "RECONCILIATION_MISMATCH",
                "ACTUAL_MISSING", "RECONCILIATION_RESULT", "129", json, true, false, null);
    }

    @Test
    void 알려진_증거_필드만_한글_라벨과_콤마_금액으로_푼다() {
        var items = withEvidence("""
                {"resultType":"ACTUAL_MISSING","paymentStage":"GA_TO_FC",
                 "differenceAmount":-5000.00,"expectedTotalAmount":5000.00,
                 "matchGroupKey":"GA_TO_FC:2:202607","reconciliationResultId":129}
                """).evidenceItems();

        assertThat(items).extracting(ExceptionOccurrenceResponse.EvidenceItem::label)
                .containsExactly("지급단계", "결과유형", "예상금액", "차액");
        assertThat(items).extracting(ExceptionOccurrenceResponse.EvidenceItem::value)
                .containsExactly("GA→설계사", "실제 지급 없음", "5,000원", "-5,000원");
    }

    /** CAP·차익거래 증거는 resultType 이 아니라 resultStatus 키를 쓴다 — 둘 다 풀린다. */
    @ParameterizedTest
    @CsvSource({
            "CAP_CHECK,NORMAL,정상",
            "CAP_CHECK,WARNING,주의",
            "CAP_CHECK,VIOLATION,위반",
            "CAP_CHECK,REVIEW_REQUIRED,검토필요",
            "CAP_CHECK,NOT_APPLICABLE,적용대상 아님",
            "ARBITRAGE_CHECK,CLEAR,이상없음",
            "ARBITRAGE_CHECK,CANDIDATE,검토대상",
            "ARBITRAGE_CHECK,REVIEW_REQUIRED,자료부족",
            "ARBITRAGE_CHECK,NOT_APPLICABLE,적용대상 아님"
    })
    void CAP_차익거래_증거의_resultStatus_키도_푼다(String sourceType, String status, String label) {
        String evidenceJson = "{\"paymentStage\":\"INSURER_TO_GA\",\"resultStatus\":\"" + status
                + "\",\"limitAmount\":1000}";
        var response = new ExceptionOccurrenceResponse(1L, 2L, 3, null, null,
                null, sourceType, "129", evidenceJson, true, false, null);
        var items = response.evidenceItems();

        assertThat(items).extracting(ExceptionOccurrenceResponse.EvidenceItem::label)
                .containsExactly("지급단계", "결과상태", "한도액");
        assertThat(items).extracting(ExceptionOccurrenceResponse.EvidenceItem::value)
                .containsExactly("원수사→GA", label, "1,000원");
        assertThat(response.evidenceJson()).isEqualTo(evidenceJson);
        assertThat(response.detectionLabel()).isEqualTo("신규");
    }

    @Test
    void 알수없는_코드와_이미_번역된_값은_원문을_유지하고_null은_생략한다() {
        assertThat(withEvidence("""
                {"paymentStage":"FUTURE_STAGE","resultStatus":"FUTURE_STATUS"}
                """).evidenceItems()).extracting(ExceptionOccurrenceResponse.EvidenceItem::value)
                .containsExactly("FUTURE_STAGE", "FUTURE_STATUS");
        assertThat(withEvidence("""
                {"paymentStage":"원수사→GA","resultStatus":"위반"}
                """).evidenceItems()).extracting(ExceptionOccurrenceResponse.EvidenceItem::value)
                .containsExactly("원수사→GA", "위반");
        assertThat(withEvidence("""
                {"paymentStage":"","resultStatus":null}
                """).evidenceItems()).extracting(ExceptionOccurrenceResponse.EvidenceItem::value)
                .containsExactly("");
    }

    @Test
    void 증거가_없거나_파싱이_안_되면_빈_목록이다() {
        assertThat(withEvidence(null).evidenceItems()).isEmpty();
        assertThat(withEvidence(" ").evidenceItems()).isEmpty();
        assertThat(withEvidence("not-json").evidenceItems()).isEmpty();
        assertThat(withEvidence("{}").evidenceItems()).isEmpty();
    }
}
