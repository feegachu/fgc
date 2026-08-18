package com.susukkang.fgc.exceptioncase.dto;

import org.junit.jupiter.api.Test;

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
                .containsExactly("GA_TO_FC", "실제 지급 없음", "5,000원", "-5,000원");
    }

    /** CAP·차익거래 증거는 resultType 이 아니라 resultStatus 키를 쓴다 — 둘 다 풀린다. */
    @Test
    void CAP_차익거래_증거의_resultStatus_키도_푼다() {
        var items = withEvidence("{\"resultStatus\":\"VIOLATION\",\"limitAmount\":1000}").evidenceItems();

        assertThat(items).extracting(ExceptionOccurrenceResponse.EvidenceItem::label)
                .containsExactly("결과상태", "한도액");
        assertThat(items).extracting(ExceptionOccurrenceResponse.EvidenceItem::value)
                .containsExactly("VIOLATION", "1,000원");
    }

    @Test
    void 증거가_없거나_파싱이_안_되면_빈_목록이다() {
        assertThat(withEvidence(null).evidenceItems()).isEmpty();
        assertThat(withEvidence(" ").evidenceItems()).isEmpty();
        assertThat(withEvidence("not-json").evidenceItems()).isEmpty();
        assertThat(withEvidence("{}").evidenceItems()).isEmpty();
    }
}
