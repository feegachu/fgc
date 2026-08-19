package com.susukkang.fgc.validation.dto;

import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.common.web.PageResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** ValidationRunListRow(매퍼 결과) → API 응답 조립이 SIR-008(코드값+라벨)을 지키는지 확인 */
class ValidationRunItemResponseTest {

    private ValidationRunListRow sampleRow() {
        ValidationRunListRow row = new ValidationRunListRow();
        row.setValidationRunId(100L);
        row.setValidationMonth(LocalDate.of(2026, 8, 1));
        row.setRunNo(1);
        row.setRunType("MONTHLY");
        row.setStatus("FAILED");
        row.setCurrentStep(3);
        row.setTriggeredBy("settle01");
        row.setStartedAt(OffsetDateTime.parse("2026-08-01T00:00:00+09:00"));
        row.setCompletedAt(null);
        row.setFinalizedBy(null);
        row.setFinalizedAt(null);
        row.setFailureMessage("배치 3단계에서 예외 발생");
        return row;
    }

    @Test
    void fromMapsCodeAndLabelTogether() {
        ValidationRunListRow row = sampleRow();
        row.setStartedAt(OffsetDateTime.parse("2026-08-17T06:23:00Z"));

        ValidationRunItemResponse response = ValidationRunItemResponse.from(row);

        assertThat(response.validationRunId()).isEqualTo(100L);
        assertThat(response.runType()).isEqualTo(ValidationRunType.MONTHLY);
        assertThat(response.runTypeLabel()).isEqualTo("월간");
        assertThat(response.status()).isEqualTo(ValidationRunStatus.FAILED);
        assertThat(response.statusLabel()).isEqualTo("실패");
        assertThat(response.currentStep()).isEqualTo(3);
        assertThat(response.triggeredBy()).isEqualTo("settle01");
        assertThat(response.startedAt())
                .isEqualTo(OffsetDateTime.parse("2026-08-17T15:23:00+09:00"));
        assertThat(response.finalizedBy()).isNull();
        assertThat(response.failureMessage()).isEqualTo("배치 3단계에서 예외 발생");
    }

    @Test
    void searchResponseFromCopiesPagingFieldsAndMapsContent() {
        PageResponse<ValidationRunListRow> page =
                PageResponse.of(List.of(sampleRow()), 1, 20, 1, "validationMonth,desc");

        ValidationRunSearchResponse response = ValidationRunSearchResponse.from(page);

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).validationRunId()).isEqualTo(100L);
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.sort()).isEqualTo("validationMonth,desc");
    }

    @Test
    void searchResponseFromHandlesEmptyContent() {
        PageResponse<ValidationRunListRow> page =
                PageResponse.of(List.of(), 1, 20, 0, "validationMonth,desc");

        ValidationRunSearchResponse response = ValidationRunSearchResponse.from(page);

        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
        assertThat(response.totalPages()).isZero();
    }
}
