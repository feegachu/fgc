package com.susukkang.fgc.reconciliation.service;

import com.susukkang.fgc.reconciliation.dto.ReconciliationRunHistoryResponse;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunHistoryRow;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunSearchCriteria;
import com.susukkang.fgc.reconciliation.mapper.ReconciliationRunHistoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * FGC-FUN-051 — 일치율 계산(대상 0건 처리 포함)과 라벨 변환을 순수 로직 단위로 검증한다.
 * DB 없이 Mockito로 Mapper를 대체한다 — SQL 정합성은 ReconciliationRunHistoryMapperIntegrationTest가 맡는다.
 */
@ExtendWith(MockitoExtension.class)
class ReconciliationRunHistoryServiceImplTest {

    @Mock
    private ReconciliationRunHistoryMapper reconciliationRunHistoryMapper;

    private ReconciliationRunHistoryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ReconciliationRunHistoryServiceImpl(reconciliationRunHistoryMapper);
    }

    private ReconciliationRunHistoryRow row(long targetCount, long matchedCount, long exceptionCount) {
        ReconciliationRunHistoryRow row = new ReconciliationRunHistoryRow();
        row.setReconciliationRunId(1L);
        row.setValidationRunId(2L);
        row.setSettlementMonth(LocalDate.of(2026, 7, 1));
        row.setPaymentStage("GA_TO_FC");
        row.setInsurerId(3L);
        row.setInsurerName("테스트생명");
        row.setStatus("COMPLETED");
        row.setTargetCount(targetCount);
        row.setMatchedCount(matchedCount);
        row.setExceptionCount(exceptionCount);
        row.setExpectedTotal(BigDecimal.valueOf(100000));
        row.setActualTotal(BigDecimal.valueOf(90000));
        row.setDifferenceTotal(BigDecimal.valueOf(10000));
        return row;
    }

    @Test
    void returnsNullMatchRateWhenTargetCountIsZero() {
        given(reconciliationRunHistoryMapper.search(null, null)).willReturn(List.of(row(0, 0, 0)));

        List<ReconciliationRunHistoryResponse> result = service.findHistory(new ReconciliationRunSearchCriteria(null, null));

        assertThat(result).singleElement().satisfies(response ->
                assertThat(response.matchRatePct()).isNull());
    }

    @Test
    void calculatesMatchRatePctRoundedToOneDecimal() {
        // 3건 중 2건 일치 = 66.6666...% → 소수점 첫째 자리 반올림으로 66.7
        given(reconciliationRunHistoryMapper.search(null, null)).willReturn(List.of(row(3, 2, 1)));

        List<ReconciliationRunHistoryResponse> result = service.findHistory(new ReconciliationRunSearchCriteria(null, null));

        assertThat(result).singleElement().satisfies(response ->
                assertThat(response.matchRatePct()).isEqualByComparingTo("66.7"));
    }

    @Test
    void calculatesFullMatchRateAsHundred() {
        given(reconciliationRunHistoryMapper.search(null, null)).willReturn(List.of(row(5, 5, 0)));

        List<ReconciliationRunHistoryResponse> result = service.findHistory(new ReconciliationRunSearchCriteria(null, null));

        assertThat(result).singleElement().satisfies(response ->
                assertThat(response.matchRatePct()).isEqualByComparingTo("100.0"));
    }

    @Test
    void mapsPaymentStageAndStatusLabels() {
        given(reconciliationRunHistoryMapper.search(null, null)).willReturn(List.of(row(1, 1, 0)));

        List<ReconciliationRunHistoryResponse> result = service.findHistory(new ReconciliationRunSearchCriteria(null, null));

        assertThat(result).singleElement().satisfies(response -> {
            assertThat(response.paymentStage()).isEqualTo("GA_TO_FC");
            assertThat(response.paymentStageLabel()).isEqualTo("GA→설계사");
            assertThat(response.status()).isEqualTo("COMPLETED");
            assertThat(response.statusLabel()).isEqualTo("계산완료");
        });
    }

    @Test
    void returnsEmptyListWhenMapperFindsNothing() {
        given(reconciliationRunHistoryMapper.search(null, null)).willReturn(List.of());

        List<ReconciliationRunHistoryResponse> result = service.findHistory(new ReconciliationRunSearchCriteria(null, null));

        assertThat(result).isEmpty();
    }
}
