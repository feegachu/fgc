package com.susukkang.fgc.reconciliation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.reconciliation.dto.ReconciliationMatchDetailRow;
import com.susukkang.fgc.reconciliation.dto.ReconciliationResultDetailRow;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunRow;
import com.susukkang.fgc.reconciliation.dto.ReconciliationSummaryRow;
import com.susukkang.fgc.reconciliation.mapper.ReconciliationResultMapper;
import com.susukkang.fgc.reconciliation.mapper.ReconciliationRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ReconciliationResultQueryServiceTest {

    @Mock
    private ReconciliationRunMapper runMapper;
    @Mock
    private ReconciliationResultMapper resultMapper;

    private ReconciliationResultQueryService service;

    @BeforeEach
    void setUp() {
        service = new ReconciliationResultQueryService(runMapper, resultMapper, new ObjectMapper());
    }

    @Test
    void emptyResultListReturns200ShapeWithZeroPageTotals() {
        given(runMapper.findById(41L)).willReturn(new ReconciliationRunRow());
        given(resultMapper.findResults(41L, null, "desc", 0, 20)).willReturn(List.of());
        given(resultMapper.countResults(41L, null)).willReturn(0L);
        given(resultMapper.findSummary(41L)).willReturn(summary());

        var response = service.search(41L, null, 1, 20, "createdAt,desc");

        assertThat(response.items().content()).isEmpty();
        assertThat(response.items().totalElements()).isZero();
        assertThat(response.items().totalPages()).isZero();
        assertThat(response.summary().exceptionCount()).isZero();
    }

    @Test
    void nonexistentRunReturnsCommonNotFound() {
        assertThatThrownBy(() -> service.search(404L, null, 1, 20, "createdAt,desc"))
                .isInstanceOfSatisfying(FgcBusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.COMMON_004));
    }

    @Test
    void unsupportedSortReturnsCommonValidationError() {
        assertThatThrownBy(() -> service.search(41L, null, 1, 20, "differenceAmount,desc"))
                .isInstanceOfSatisfying(FgcBusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.COMMON_002);
                    assertThat(exception.getField()).isEqualTo("sort");
                });
    }

    @Test
    void detailReturnsComparisonSnapshotAndReasonLabels() {
        ReconciliationResultDetailRow row = new ReconciliationResultDetailRow();
        row.setReconciliationResultId(77L);
        row.setResultType("REVIEW_REQUIRED");
        row.setPrimaryReasonCode("UNKNOWN");
        row.setSecondaryReasonCodesCsv("AMOUNT_DIFFERENCE,AGENT_MISMATCH");
        row.setDetailSnapshotJson("{\"paymentStage\":\"GA_TO_FC\"}");
        ReconciliationMatchDetailRow match = new ReconciliationMatchDetailRow();
        match.setScheduleLineId(11L);
        match.setTransactionAttributionId(21L);
        match.setCommissionTransactionId(31L);
        match.setDueDate(LocalDate.of(2026, 8, 10));
        match.setBasisAmount(new BigDecimal("100000"));
        match.setRatePct(new BigDecimal("650.0000"));
        match.setAttributionDate(LocalDate.of(2026, 8, 3));
        match.setSettlementMonth(LocalDate.of(2026, 8, 1));
        given(resultMapper.findDetail(77L)).willReturn(row);
        given(resultMapper.findMatches(77L)).willReturn(List.of(match));

        var response = service.get(77L);

        assertThat(response.resultTypeLabel()).isNotBlank();
        assertThat(response.primaryReason().label()).isEqualTo("분류 불가");
        assertThat(response.secondaryReasons()).extracting("code")
                .containsExactly("AMOUNT_DIFFERENCE", "AGENT_MISMATCH");
        assertThat(response.matches().getFirst().commissionTransactionId()).isEqualTo(31L);
        assertThat(response.matches().getFirst().dueDate()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(response.matches().getFirst().basisAmount()).isEqualByComparingTo("100000");
        assertThat(response.matches().getFirst().ratePct()).isEqualByComparingTo("650.0000");
        assertThat(response.matches().getFirst().attributionDate()).isEqualTo(LocalDate.of(2026, 8, 3));
        assertThat(response.matches().getFirst().settlementMonth()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(response.detailSnapshot().get("paymentStage").asText()).isEqualTo("GA_TO_FC");
    }

    private static ReconciliationSummaryRow summary() {
        ReconciliationSummaryRow row = new ReconciliationSummaryRow();
        row.setExpectedTotal(BigDecimal.ZERO);
        row.setActualTotal(BigDecimal.ZERO);
        row.setDifferenceTotal(BigDecimal.ZERO);
        return row;
    }
}
