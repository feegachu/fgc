package com.susukkang.fgc.reconciliation.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.web.PageResponse;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * FGC-FUN-051 — 일치율 계산(대상 0건 처리 포함)과 라벨 변환, 페이징 파라미터 검증을
 * 순수 로직 단위로 검증한다. DB 없이 Mockito로 Mapper를 대체한다 — SQL 정합성은
 * ReconciliationRunHistoryMapperIntegrationTest가 맡는다.
 */
@ExtendWith(MockitoExtension.class)
class ReconciliationRunHistoryServiceImplTest {

    private static final ReconciliationRunSearchCriteria NO_FILTER =
            new ReconciliationRunSearchCriteria(null, null);

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
        given(reconciliationRunHistoryMapper.search(null, null, "desc", 0, 20))
                .willReturn(List.of(row(0, 0, 0)));

        PageResponse<ReconciliationRunHistoryResponse> result =
                service.findHistory(NO_FILTER, 1, 20, "createdAt,desc");

        assertThat(result.content()).singleElement().satisfies(response ->
                assertThat(response.matchRatePct()).isNull());
    }

    @Test
    void calculatesMatchRatePctRoundedToOneDecimal() {
        // 3건 중 2건 일치 = 66.6666...% → 소수점 첫째 자리 반올림으로 66.7
        given(reconciliationRunHistoryMapper.search(null, null, "desc", 0, 20))
                .willReturn(List.of(row(3, 2, 1)));

        PageResponse<ReconciliationRunHistoryResponse> result =
                service.findHistory(NO_FILTER, 1, 20, "createdAt,desc");

        assertThat(result.content()).singleElement().satisfies(response ->
                assertThat(response.matchRatePct()).isEqualByComparingTo("66.7"));
    }

    @Test
    void roundsOnceInsteadOfCompoundingIntermediateRounding() {
        // 코드리뷰 반영 회귀 테스트 — matched=12449, target=100000의 정확한 비율은
        // 12.449%다. 소수 넷째 자리에서 먼저 반올림한 뒤 다시 소수 첫째 자리로
        // 반올림하면(두 번 반올림) 12.45 → 12.5로 틀어진다. 한 번에 반올림하면 12.4가
        // 맞는 값이다.
        given(reconciliationRunHistoryMapper.search(null, null, "desc", 0, 20))
                .willReturn(List.of(row(100000, 12449, 87551)));

        PageResponse<ReconciliationRunHistoryResponse> result =
                service.findHistory(NO_FILTER, 1, 20, "createdAt,desc");

        assertThat(result.content()).singleElement().satisfies(response ->
                assertThat(response.matchRatePct()).isEqualByComparingTo("12.4"));
    }

    @Test
    void calculatesFullMatchRateAsHundred() {
        given(reconciliationRunHistoryMapper.search(null, null, "desc", 0, 20))
                .willReturn(List.of(row(5, 5, 0)));

        PageResponse<ReconciliationRunHistoryResponse> result =
                service.findHistory(NO_FILTER, 1, 20, "createdAt,desc");

        assertThat(result.content()).singleElement().satisfies(response ->
                assertThat(response.matchRatePct()).isEqualByComparingTo("100.0"));
    }

    @Test
    void mapsPaymentStageAndStatusLabels() {
        given(reconciliationRunHistoryMapper.search(null, null, "desc", 0, 20))
                .willReturn(List.of(row(1, 1, 0)));

        PageResponse<ReconciliationRunHistoryResponse> result =
                service.findHistory(NO_FILTER, 1, 20, "createdAt,desc");

        assertThat(result.content()).singleElement().satisfies(response -> {
            assertThat(response.paymentStage()).isEqualTo("GA_TO_FC");
            assertThat(response.paymentStageLabel()).isEqualTo("GA→설계사");
            assertThat(response.status()).isEqualTo("COMPLETED");
            assertThat(response.statusLabel()).isEqualTo("계산완료");
        });
    }

    @Test
    void returnsEmptyPageWhenMapperFindsNothing() {
        given(reconciliationRunHistoryMapper.search(null, null, "desc", 0, 20))
                .willReturn(List.of());
        given(reconciliationRunHistoryMapper.count(null, null)).willReturn(0L);

        PageResponse<ReconciliationRunHistoryResponse> result =
                service.findHistory(NO_FILTER, 1, 20, "createdAt,desc");

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
    }

    @Test
    void passesSettlementMonthAndPaymentStageFiltersThrough() {
        ReconciliationRunSearchCriteria criteria =
                new ReconciliationRunSearchCriteria(LocalDate.of(2026, 7, 1), "GA_TO_FC");
        given(reconciliationRunHistoryMapper.search(
                LocalDate.of(2026, 7, 1), "GA_TO_FC", "desc", 0, 20))
                .willReturn(List.of());

        service.findHistory(criteria, 1, 20, "createdAt,desc");

        verify(reconciliationRunHistoryMapper)
                .search(eq(LocalDate.of(2026, 7, 1)), eq("GA_TO_FC"), eq("desc"), eq(0), eq(20));
        verify(reconciliationRunHistoryMapper)
                .count(eq(LocalDate.of(2026, 7, 1)), eq("GA_TO_FC"));
    }

    @Test
    void ascendingSortIsPassedThroughAsDirection() {
        given(reconciliationRunHistoryMapper.search(null, null, "asc", 0, 20))
                .willReturn(List.of());

        service.findHistory(NO_FILTER, 1, 20, "createdAt,asc");

        verify(reconciliationRunHistoryMapper).search(null, null, "asc", 0, 20);
    }

    @Test
    void secondPageUsesOffsetOfSizeTimesPageMinusOne() {
        given(reconciliationRunHistoryMapper.search(null, null, "desc", 40, 20))
                .willReturn(List.of());

        service.findHistory(NO_FILTER, 3, 20, "createdAt,desc");

        verify(reconciliationRunHistoryMapper).search(null, null, "desc", 40, 20);
    }

    @Test
    void throwsCommon002WhenPageIsLessThanOne() {
        assertThatThrownBy(() -> service.findHistory(NO_FILTER, 0, 20, "createdAt,desc"))
                .isInstanceOf(FgcBusinessException.class)
                .satisfies(exception ->
                        assertThat(((FgcBusinessException) exception).getField()).isEqualTo("page"));
        verifyNoInteractions(reconciliationRunHistoryMapper);
    }

    @Test
    void throwsCommon002WhenSizeIsOutOfRange() {
        assertThatThrownBy(() -> service.findHistory(NO_FILTER, 1, 0, "createdAt,desc"))
                .isInstanceOf(FgcBusinessException.class)
                .satisfies(exception ->
                        assertThat(((FgcBusinessException) exception).getField()).isEqualTo("size"));

        assertThatThrownBy(() -> service.findHistory(NO_FILTER, 1, 101, "createdAt,desc"))
                .isInstanceOf(FgcBusinessException.class)
                .satisfies(exception ->
                        assertThat(((FgcBusinessException) exception).getField()).isEqualTo("size"));
    }

    @Test
    void throwsCommon002WhenSortIsNotSupported() {
        assertThatThrownBy(() -> service.findHistory(NO_FILTER, 1, 20, "insurerName,asc"))
                .isInstanceOf(FgcBusinessException.class)
                .satisfies(exception ->
                        assertThat(((FgcBusinessException) exception).getField()).isEqualTo("sort"));
    }
}
