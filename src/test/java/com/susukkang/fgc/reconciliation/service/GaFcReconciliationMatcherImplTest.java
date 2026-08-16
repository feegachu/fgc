package com.susukkang.fgc.reconciliation.service;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.reconciliation.domain.ReconciliationResultType;
import com.susukkang.fgc.reconciliation.dto.GaFcActualSourceRow;
import com.susukkang.fgc.reconciliation.dto.GaFcExpectedSourceRow;
import com.susukkang.fgc.reconciliation.dto.GaFcMatchCandidate;
import com.susukkang.fgc.reconciliation.mapper.GaFcReconciliationMapper;
import com.susukkang.fgc.reconciliation.port.ReconciliationExecutionRequest;
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

/**
 * 설명 : FGC-FUN-048-03 GA→FC 예상 스케줄·확정 지급 건 매칭 단위 테스트
 *
 * @author yslee
 * @since 2026-08-13
 * @version 1.2
 */
@ExtendWith(MockitoExtension.class)
class GaFcReconciliationMatcherImplTest {

    private static final LocalDate MONTH = LocalDate.of(2026, 8, 1);

    @Mock
    private GaFcReconciliationMapper reconciliationMapper;

    private GaFcReconciliationMatcherImpl matcher;

    @BeforeEach
    void setUp() {
        matcher = new GaFcReconciliationMatcherImpl(
                reconciliationMapper,
                new ZeroTolerancePolicy()
        );
    }

    @Test
    void 계약_설계사_항목_회차_날짜와_금액이_같으면_MATCHED다() {
        given(reconciliationMapper.findExpectedSources(MONTH, 3L))
                .willReturn(List.of(expected(11L, 501L, 1, "650000", 101L)));
        given(reconciliationMapper.findActualSources(MONTH, 3L))
                .willReturn(List.of(actual(21L, 501L, 1, "650000", 201L)));

        GaFcMatchCandidate result = matcher.match(request()).getFirst();

        assertThat(result.resultType()).isEqualTo(ReconciliationResultType.MATCHED);
        assertThat(result.differenceAmount()).isEqualByComparingTo("0");
        assertThat(result.scheduleLineIds()).containsExactly(11L);
        assertThat(result.transactionAttributionIds()).containsExactly(21L);
        assertThat(result.expectedJournalHeaderIds()).containsExactly(101L);
        assertThat(result.actualJournalHeaderIds()).containsExactly(201L);
    }

    @Test
    void 실제_지급액이_1원_작으면_AMOUNT_DIFFERENCE다() {
        given(reconciliationMapper.findExpectedSources(MONTH, 3L))
                .willReturn(List.of(expected(11L, 501L, 1, "650000", null)));
        given(reconciliationMapper.findActualSources(MONTH, 3L))
                .willReturn(List.of(actual(21L, 501L, 1, "649999", null)));

        GaFcMatchCandidate result = matcher.match(request()).getFirst();

        assertThat(result.resultType()).isEqualTo(ReconciliationResultType.AMOUNT_DIFFERENCE);
        assertThat(result.differenceAmount()).isEqualByComparingTo("-1");
    }

    @Test
    void 실제가_없으면_ACTUAL_MISSING이고_예상이_없으면_EXPECTED_MISSING이다() {
        GaFcActualSourceRow actual = actual(21L, 501L, 1, "100000", null);
        actual.setContractId(999L);
        given(reconciliationMapper.findExpectedSources(MONTH, 3L))
                .willReturn(List.of(expected(11L, 501L, 1, "650000", null)));
        given(reconciliationMapper.findActualSources(MONTH, 3L)).willReturn(List.of(actual));

        List<GaFcMatchCandidate> results = matcher.match(request());

        assertThat(results).extracting(GaFcMatchCandidate::resultType)
                .containsExactly(ReconciliationResultType.ACTUAL_MISSING, ReconciliationResultType.EXPECTED_MISSING);
    }

    @Test
    void 같은_키의_확정_지급_귀속행이_둘이면_DUPLICATE다() {
        given(reconciliationMapper.findExpectedSources(MONTH, 3L))
                .willReturn(List.of(expected(11L, 501L, 1, "650000", null)));
        given(reconciliationMapper.findActualSources(MONTH, 3L))
                .willReturn(List.of(
                        actual(21L, 501L, 1, "300000", null),
                        actual(22L, 501L, 1, "350000", null)
                ));

        GaFcMatchCandidate result = matcher.match(request()).getFirst();

        assertThat(result.resultType()).isEqualTo(ReconciliationResultType.DUPLICATE);
        assertThat(result.actualTotalAmount()).isEqualByComparingTo("650000");
        assertThat(result.transactionAttributionIds()).containsExactly(21L, 22L);
    }

    @Test
    void 수취_설계사가_다르면_AGENT_MISMATCH다() {
        given(reconciliationMapper.findExpectedSources(MONTH, 3L))
                .willReturn(List.of(expected(11L, 501L, 1, "650000", null)));
        given(reconciliationMapper.findActualSources(MONTH, 3L))
                .willReturn(List.of(actual(21L, 502L, 1, "650000", null)));

        GaFcMatchCandidate result = matcher.match(request()).getFirst();

        assertThat(result.resultType()).isEqualTo(ReconciliationResultType.AGENT_MISMATCH);
        assertThat(result.expectedAgentId()).isEqualTo(501L);
        assertThat(result.actualAgentId()).isEqualTo(502L);
    }

    @Test
    void 예상_13회차와_실제_14회차는_INSTALLMENT_MISMATCH다() {
        given(reconciliationMapper.findExpectedSources(MONTH, 3L))
                .willReturn(List.of(expected(11L, 501L, 13, "650000", null)));
        given(reconciliationMapper.findActualSources(MONTH, 3L))
                .willReturn(List.of(actual(21L, 501L, 14, "650000", null)));

        GaFcMatchCandidate result = matcher.match(request()).getFirst();

        assertThat(result.resultType()).isEqualTo(ReconciliationResultType.INSTALLMENT_MISMATCH);
        assertThat(result.installmentNo()).isEqualTo(13);
        assertThat(result.actualInstallmentNo()).isEqualTo(14);
    }

    // 2026-08-13 yslee - 기존 지급 건의 실제 회차 누락 회귀 검증
    // 기존 코드: V18 이전 지급 건이나 회차 입력이 없는 수기 지급 건의 회차를 비교할 수 없음
    // 문제: 예정일로 회차를 추정하면 정책·스케줄 변경 시 다른 회차를 정상일치로 오판할 수 있음
    // 개선: 양쪽 원천이 있으나 실제 회차가 없으면 자동 불일치 대신 REVIEW_REQUIRED로 보존
    @Test
    void 실제_회차가_없으면_임의_추정하지_않고_REVIEW_REQUIRED다() {
        given(reconciliationMapper.findExpectedSources(MONTH, 3L))
                .willReturn(List.of(expected(11L, 501L, 13, "650000", null)));
        given(reconciliationMapper.findActualSources(MONTH, 3L))
                .willReturn(List.of(actual(21L, 501L, null, "650000", null)));

        GaFcMatchCandidate result = matcher.match(request()).getFirst();

        assertThat(result.resultType()).isEqualTo(ReconciliationResultType.REVIEW_REQUIRED);
        assertThat(result.secondaryReasonCodes())
                .containsExactly(ReconciliationResultType.INSTALLMENT_MISMATCH.name());
    }

    @Test
    void 실제_수취_설계사가_없으면_REVIEW_REQUIRED다() {
        given(reconciliationMapper.findExpectedSources(MONTH, 3L))
                .willReturn(List.of(expected(11L, 501L, 1, "650000", null)));
        given(reconciliationMapper.findActualSources(MONTH, 3L))
                .willReturn(List.of(actual(21L, null, 1, "650000", null)));

        GaFcMatchCandidate result = matcher.match(request()).getFirst();

        assertThat(result.resultType()).isEqualTo(ReconciliationResultType.REVIEW_REQUIRED);
        // 2026-08-13 yslee - 설계사 식별 불가 원인의 보조 사유 회귀 검증
        // 기존 코드: REVIEW_REQUIRED 주 결과만 확인해 구체적인 검토 원인 소실을 발견하지 못함
        // 문제: 후속 FUN-048-04 저장 단계에서 설계사 문제를 추적할 수 없음
        // 개선: 보조 사유에 AGENT_MISMATCH가 남는지 함께 검증
        assertThat(result.secondaryReasonCodes())
                .containsExactly(ReconciliationResultType.AGENT_MISMATCH.name());
    }

    @Test
    void 지급예정일이_다르면_서로_다른_누락_후보로_분리한다() {
        GaFcActualSourceRow actual = actual(21L, 501L, 1, "650000", null);
        actual.setDueDate(MONTH.plusDays(15));
        given(reconciliationMapper.findExpectedSources(MONTH, 3L))
                .willReturn(List.of(expected(11L, 501L, 1, "650000", null)));
        given(reconciliationMapper.findActualSources(MONTH, 3L)).willReturn(List.of(actual));

        List<GaFcMatchCandidate> results = matcher.match(request());

        assertThat(results).extracting(GaFcMatchCandidate::resultType)
                .containsExactly(ReconciliationResultType.ACTUAL_MISSING, ReconciliationResultType.EXPECTED_MISSING);
    }

    // 2026-08-14 yslee - FGC-FUN-050 예상 지급예정일 누락 회귀 검증
    // 기존 코드: 예상 지급예정일 null 그룹을 실제 지급 건과 분리한 뒤 ACTUAL_MISSING으로 확정
    // 문제: 비교 기준이 없는 원천을 누락으로 집계해 수동 검토 대상이 사라짐
    // 개선: null 날짜 예상 원천 후보는 REVIEW_REQUIRED이고 누락 보조 사유를 남기지 않는지 검증
    @Test
    void 예상_지급예정일이_없으면_ACTUAL_MISSING으로_단정하지_않고_REVIEW_REQUIRED다() {
        GaFcExpectedSourceRow expected = expected(11L, 501L, 1, "650000", null);
        expected.setDueDate(null);
        given(reconciliationMapper.findExpectedSources(MONTH, 3L)).willReturn(List.of(expected));
        given(reconciliationMapper.findActualSources(MONTH, 3L))
                .willReturn(List.of(actual(21L, 501L, 1, "650000", null)));

        List<GaFcMatchCandidate> results = matcher.match(request());
        GaFcMatchCandidate reviewRequired = results.stream()
                .filter(candidate -> candidate.dueDate() == null)
                .findFirst()
                .orElseThrow();

        assertThat(reviewRequired.resultType()).isEqualTo(ReconciliationResultType.REVIEW_REQUIRED);
        assertThat(reviewRequired.secondaryReasonCodes()).isEmpty();
        assertThat(results).extracting(GaFcMatchCandidate::resultType)
                .containsExactlyInAnyOrder(
                        ReconciliationResultType.REVIEW_REQUIRED,
                        ReconciliationResultType.EXPECTED_MISSING);
    }

    // 2026-08-14 yslee - FGC-FUN-050 비영 날짜 정책의 복수 후보 안전성 검증
    // 기존 코드: 허용 범위에 예상일이 둘이면 정렬상 첫 키에 실제 원천을 임의 연결
    // 문제: 비교 기준을 확정할 수 없는 실제 지급이 MATCHED로 숨겨질 수 있음
    // 개선: 복수 날짜 후보에 걸친 실제 원천은 별도 REVIEW_REQUIRED 후보로 보존
    @Test
    void 날짜_허용범위에_예상일이_둘이면_임의_매칭하지_않고_REVIEW_REQUIRED다() {
        GaFcExpectedSourceRow firstExpected = expected(11L, 501L, 1, "650000", null);
        GaFcExpectedSourceRow secondExpected = expected(12L, 501L, 2, "650000", null);
        secondExpected.setDueDate(MONTH.plusDays(16));
        GaFcActualSourceRow actual = actual(21L, 501L, 1, "650000", null);
        actual.setDueDate(MONTH.plusDays(15));
        given(reconciliationMapper.findExpectedSources(MONTH, 3L))
                .willReturn(List.of(firstExpected, secondExpected));
        given(reconciliationMapper.findActualSources(MONTH, 3L)).willReturn(List.of(actual));
        GaFcReconciliationMatcherImpl tolerantMatcher = new GaFcReconciliationMatcherImpl(
                reconciliationMapper, oneDayTolerancePolicy());

        List<GaFcMatchCandidate> results = tolerantMatcher.match(request());
        GaFcMatchCandidate reviewRequired = results.stream()
                .filter(candidate -> MONTH.plusDays(15).equals(candidate.dueDate()))
                .findFirst()
                .orElseThrow();

        assertThat(reviewRequired.resultType()).isEqualTo(ReconciliationResultType.REVIEW_REQUIRED);
        assertThat(reviewRequired.transactionAttributionIds()).containsExactly(21L);
        assertThat(reviewRequired.secondaryReasonCodes()).isEmpty();
        assertThat(results).extracting(GaFcMatchCandidate::resultType)
                .doesNotContain(ReconciliationResultType.MATCHED);
    }

    @Test
    void 서로_다른_실제일이_같은_예상일_허용범위에_들면_합산하지_않고_REVIEW_REQUIRED다() {
        GaFcExpectedSourceRow expected = expected(11L, 501L, 1, "650000", null);
        expected.setDueDate(MONTH.plusDays(15));
        GaFcActualSourceRow firstActual = actual(21L, 501L, 1, "325000", null);
        firstActual.setDueDate(MONTH.plusDays(14));
        GaFcActualSourceRow secondActual = actual(22L, 501L, 1, "325000", null);
        secondActual.setDueDate(MONTH.plusDays(16));
        given(reconciliationMapper.findExpectedSources(MONTH, 3L)).willReturn(List.of(expected));
        given(reconciliationMapper.findActualSources(MONTH, 3L))
                .willReturn(List.of(firstActual, secondActual));
        GaFcReconciliationMatcherImpl tolerantMatcher = new GaFcReconciliationMatcherImpl(
                reconciliationMapper, oneDayTolerancePolicy());

        List<GaFcMatchCandidate> reviewCandidates = tolerantMatcher.match(request()).stream()
                .filter(candidate -> candidate.resultType() == ReconciliationResultType.REVIEW_REQUIRED)
                .toList();

        assertThat(reviewCandidates).hasSize(2).allSatisfy(candidate -> {
            assertThat(candidate.transactionAttributionIds()).hasSize(1);
            assertThat(candidate.secondaryReasonCodes()).isEmpty();
        });
        assertThat(reviewCandidates).flatExtracting(GaFcMatchCandidate::transactionAttributionIds)
                .containsExactlyInAnyOrder(21L, 22L);
    }

    @Test
    void 상세행은_합산_전에_원단위_HALF_UP으로_반올림한다() {
        given(reconciliationMapper.findExpectedSources(MONTH, 3L))
                .willReturn(List.of(expected(11L, 501L, 1, "0.5", null)));
        given(reconciliationMapper.findActualSources(MONTH, 3L))
                .willReturn(List.of(actual(21L, 501L, 1, "1", null)));

        GaFcMatchCandidate result = matcher.match(request()).getFirst();

        assertThat(result.resultType()).isEqualTo(ReconciliationResultType.MATCHED);
        assertThat(result.expectedTotalAmount()).isEqualByComparingTo("1");
        assertThat(result.actualTotalAmount()).isEqualByComparingTo("1");
    }

    @Test
    void INSURER_TO_GA_요청은_FUN_048_03_범위가_아니므로_거절한다() {
        ReconciliationExecutionRequest wrongStage = new ReconciliationExecutionRequest(
                7L, 9L, MONTH, PaymentStage.INSURER_TO_GA, 3L, 5L);

        assertThatThrownBy(() -> matcher.match(wrongStage))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GA_TO_FC");
    }

    private static ReconciliationExecutionRequest request() {
        return new ReconciliationExecutionRequest(7L, 9L, MONTH, PaymentStage.GA_TO_FC, 3L, 5L);
    }

    private static TolerancePolicy oneDayTolerancePolicy() {
        return new TolerancePolicy() {
            @Override
            public boolean matchesAmount(BigDecimal expectedAmount, BigDecimal actualAmount) {
                return expectedAmount.compareTo(actualAmount) == 0;
            }

            @Override
            public boolean matchesDate(LocalDate expectedDate, LocalDate actualDate) {
                return Math.abs(expectedDate.toEpochDay() - actualDate.toEpochDay()) <= 1;
            }

            @Override
            public boolean matchesInstallment(Integer expectedInstallment, Integer actualInstallment) {
                return expectedInstallment.equals(actualInstallment);
            }
        };
    }

    private static GaFcExpectedSourceRow expected(
            Long scheduleLineId,
            Long agentId,
            Integer installmentNo,
            String amount,
            Long journalHeaderId
    ) {
        GaFcExpectedSourceRow row = new GaFcExpectedSourceRow();
        row.setScheduleLineId(scheduleLineId);
        row.setJournalHeaderId(journalHeaderId);
        row.setContractId(100L);
        row.setExpectedAgentId(agentId);
        row.setCommissionItemId(200L);
        row.setInstallmentNo(installmentNo);
        row.setDueDate(MONTH.plusDays(14));
        row.setDueMonth(MONTH);
        row.setExpectedAmount(new BigDecimal(amount));
        return row;
    }

    private static GaFcActualSourceRow actual(
            Long attributionId,
            Long agentId,
            Integer installmentNo,
            String amount,
            Long journalHeaderId
    ) {
        GaFcActualSourceRow row = new GaFcActualSourceRow();
        row.setTransactionAttributionId(attributionId);
        row.setCommissionTransactionId(attributionId + 1000);
        row.setJournalHeaderId(journalHeaderId);
        row.setContractId(100L);
        row.setActualAgentId(agentId);
        row.setCommissionItemId(200L);
        row.setActualInstallmentNo(installmentNo);
        row.setSettlementMonth(MONTH);
        row.setDueDate(MONTH.plusDays(14));
        row.setActualAmount(new BigDecimal(amount));
        row.setSourceBusinessKey("GA:TEST:" + attributionId);
        return row;
    }
}
