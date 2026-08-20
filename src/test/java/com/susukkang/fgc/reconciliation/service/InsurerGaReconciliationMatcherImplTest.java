package com.susukkang.fgc.reconciliation.service;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.reconciliation.domain.ReconciliationResultType;
import com.susukkang.fgc.reconciliation.dto.InsurerGaActualSourceRow;
import com.susukkang.fgc.reconciliation.dto.InsurerGaExpectedSourceRow;
import com.susukkang.fgc.reconciliation.dto.InsurerGaMatchCandidate;
import com.susukkang.fgc.reconciliation.mapper.InsurerGaReconciliationMapper;
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
 * 설명 : 보험사→GA 예상 스케줄·실제 명세 매칭 단위 테스트
 *
 * @author yslee
 * @since 2026-08-12
 * @version 1.2
 */
@ExtendWith(MockitoExtension.class)
class InsurerGaReconciliationMatcherImplTest {

    private static final LocalDate MONTH = LocalDate.of(2026, 8, 1);

    @Mock
    private InsurerGaReconciliationMapper reconciliationMapper;

    private InsurerGaReconciliationMatcherImpl matcher;

    @BeforeEach
    void setUp() {
        matcher = new InsurerGaReconciliationMatcherImpl(
                reconciliationMapper,
                new ZeroTolerancePolicy()
        );
    }

    @Test
    void 같은_계약_항목_월의_예상과_실제_금액이_같으면_MATCHED다() {
        given(reconciliationMapper.findExpectedSources(MONTH, 3L))
                .willReturn(List.of(expected(11L, 1, "650000", 101L)));
        given(reconciliationMapper.findActualSources(MONTH, 3L))
                .willReturn(List.of(actual(21L, "650000", 201L)));

        InsurerGaMatchCandidate result = matcher.match(request()).getFirst();

        assertThat(result.resultType()).isEqualTo(ReconciliationResultType.MATCHED);
        assertThat(result.installmentNo()).isEqualTo(1);
        assertThat(result.differenceAmount()).isEqualByComparingTo("0");
        assertThat(result.scheduleLineIds()).containsExactly(11L);
        assertThat(result.transactionAttributionIds()).containsExactly(21L);
        assertThat(result.expectedJournalHeaderIds()).containsExactly(101L);
        assertThat(result.actualJournalHeaderIds()).containsExactly(201L);
    }

    @Test
    void 실제가_1원_작으면_AMOUNT_DIFFERENCE이고_차액은_실제_빼기_예상이다() {
        given(reconciliationMapper.findExpectedSources(MONTH, 3L))
                .willReturn(List.of(expected(11L, 1, "650000", null)));
        given(reconciliationMapper.findActualSources(MONTH, 3L))
                .willReturn(List.of(actual(21L, "649999", null)));

        InsurerGaMatchCandidate result = matcher.match(request()).getFirst();

        assertThat(result.resultType()).isEqualTo(ReconciliationResultType.AMOUNT_DIFFERENCE);
        assertThat(result.differenceAmount()).isEqualByComparingTo("-1");
    }

    @Test
    void 실제가_없으면_ACTUAL_MISSING이고_예상이_없으면_EXPECTED_MISSING이다() {
        InsurerGaExpectedSourceRow expected = expected(11L, 1, "650000", null);
        InsurerGaActualSourceRow actual = actual(21L, "100000", null);
        actual.setContractId(999L);
        given(reconciliationMapper.findExpectedSources(MONTH, 3L)).willReturn(List.of(expected));
        given(reconciliationMapper.findActualSources(MONTH, 3L)).willReturn(List.of(actual));

        List<InsurerGaMatchCandidate> results = matcher.match(request());

        assertThat(results).extracting(InsurerGaMatchCandidate::resultType)
                .containsExactly(ReconciliationResultType.ACTUAL_MISSING, ReconciliationResultType.EXPECTED_MISSING);
    }

    @Test
    void 같은_키의_실제_명세가_둘이면_DUPLICATE이고_모든_ID를_보존한다() {
        given(reconciliationMapper.findExpectedSources(MONTH, 3L))
                .willReturn(List.of(expected(11L, 1, "650000", null)));
        given(reconciliationMapper.findActualSources(MONTH, 3L))
                .willReturn(List.of(actual(21L, "325000", null), actual(22L, "325000", null)));

        InsurerGaMatchCandidate result = matcher.match(request()).getFirst();

        assertThat(result.resultType()).isEqualTo(ReconciliationResultType.DUPLICATE);
        assertThat(result.actualTotalAmount()).isEqualByComparingTo("650000");
        assertThat(result.transactionAttributionIds()).containsExactly(21L, 22L);
    }

    @Test
    void 예상이_없는_실제_명세도_둘이면_DUPLICATE이고_EXPECTED_MISSING을_보조사유로_남긴다() {
        given(reconciliationMapper.findExpectedSources(MONTH, 3L)).willReturn(List.of());
        given(reconciliationMapper.findActualSources(MONTH, 3L))
                .willReturn(List.of(actual(21L, "100000", null), actual(22L, "100000", null)));

        InsurerGaMatchCandidate result = matcher.match(request()).getFirst();

        assertThat(result.resultType()).isEqualTo(ReconciliationResultType.DUPLICATE);
        assertThat(result.secondaryReasonCodes()).containsExactly(ReconciliationResultType.EXPECTED_MISSING.name());
    }

    @Test
    void 같은_월에_예상_회차가_둘이면_회차를_추정하지_않고_REVIEW_REQUIRED다() {
        given(reconciliationMapper.findExpectedSources(MONTH, 3L))
                .willReturn(List.of(
                        expected(11L, 1, "300000", null),
                        expected(12L, 2, "350000", null)
                ));
        given(reconciliationMapper.findActualSources(MONTH, 3L))
                .willReturn(List.of(actual(21L, "650000", null)));

        InsurerGaMatchCandidate result = matcher.match(request()).getFirst();

        assertThat(result.resultType()).isEqualTo(ReconciliationResultType.REVIEW_REQUIRED);
        assertThat(result.installmentNo()).isNull();
        assertThat(result.scheduleLineIds()).containsExactly(11L, 12L);
        assertThat(result.secondaryReasonCodes())
                .containsExactly(
                        ReconciliationResultType.INSTALLMENT_MISMATCH.name(),
                        ReconciliationResultType.DUPLICATE.name());
    }

    // 2026-08-13 yslee - 미확정 회차와 행별 반올림 회귀 검증
    // 기존 코드: null 회차가 정렬 중 예외를 내거나 금액이 같으면 MATCHED로 처리될 수 있었음
    // 문제: 회차 정확일치와 상세행 단위 HALF_UP 규칙을 테스트가 보장하지 못함
    // 개선: null 회차는 REVIEW_REQUIRED, 0.5원 상세행은 1원으로 반올림된 금액으로 판정
    @Test
    void 예상_회차가_null이면_금액이_같아도_REVIEW_REQUIRED다() {
        given(reconciliationMapper.findExpectedSources(MONTH, 3L))
                .willReturn(List.of(expected(11L, null, "650000", null)));
        given(reconciliationMapper.findActualSources(MONTH, 3L))
                .willReturn(List.of(actual(21L, "650000", null)));

        InsurerGaMatchCandidate result = matcher.match(request()).getFirst();

        assertThat(result.resultType()).isEqualTo(ReconciliationResultType.REVIEW_REQUIRED);
        assertThat(result.secondaryReasonCodes())
                .containsExactly(ReconciliationResultType.INSTALLMENT_MISMATCH.name());
    }

    // 2026-08-13 yslee - 예상·실제 회차 불일치 주 결과유형 회귀 테스트 추가
    // 기존 코드: 실제 원천 회차가 없어 회차 관련 결과는 REVIEW_REQUIRED의 보조 사유로만 기록
    // 문제: REC-07 예상 13회차·실제 14회차가 INSTALLMENT_MISMATCH로 집계되지 않음
    // 개선: 양쪽 단일 회차가 명확하고 서로 다르면 INSTALLMENT_MISMATCH를 주 결과로 검증
    @Test
    void 예상_13회차와_실제_14회차는_INSTALLMENT_MISMATCH다() {
        InsurerGaActualSourceRow actual = actual(21L, "650000", null);
        actual.setActualInstallmentNo(14);
        given(reconciliationMapper.findExpectedSources(MONTH, 3L))
                .willReturn(List.of(expected(11L, 13, "650000", null)));
        given(reconciliationMapper.findActualSources(MONTH, 3L)).willReturn(List.of(actual));

        InsurerGaMatchCandidate result = matcher.match(request()).getFirst();

        assertThat(result.resultType()).isEqualTo(ReconciliationResultType.INSTALLMENT_MISMATCH);
        assertThat(result.installmentNo()).isEqualTo(13);
        assertThat(result.actualInstallmentNo()).isEqualTo(14);
        assertThat(result.matchGroupKey()).contains("E13-A14");
    }

    @Test
    void 실제_회차가_없으면_불일치로_단정하지_않고_REVIEW_REQUIRED다() {
        InsurerGaActualSourceRow actual = actual(21L, "650000", null);
        actual.setActualInstallmentNo(null);
        given(reconciliationMapper.findExpectedSources(MONTH, 3L))
                .willReturn(List.of(expected(11L, 13, "650000", null)));
        given(reconciliationMapper.findActualSources(MONTH, 3L)).willReturn(List.of(actual));

        InsurerGaMatchCandidate result = matcher.match(request()).getFirst();

        assertThat(result.resultType()).isEqualTo(ReconciliationResultType.REVIEW_REQUIRED);
        assertThat(result.installmentNo()).isEqualTo(13);
        assertThat(result.actualInstallmentNo()).isNull();
    }

    @Test
    void 상세행_금액은_합산_전에_원단위_HALF_UP으로_반올림한다() {
        given(reconciliationMapper.findExpectedSources(MONTH, 3L))
                .willReturn(List.of(expected(11L, 1, "0.5", null)));
        given(reconciliationMapper.findActualSources(MONTH, 3L))
                .willReturn(List.of(actual(21L, "1", null)));

        InsurerGaMatchCandidate result = matcher.match(request()).getFirst();

        assertThat(result.resultType()).isEqualTo(ReconciliationResultType.MATCHED);
        assertThat(result.expectedTotalAmount()).isEqualByComparingTo("1");
        assertThat(result.actualTotalAmount()).isEqualByComparingTo("1");
        assertThat(result.differenceAmount()).isEqualByComparingTo("0");
    }

    // 2026-08-13 yslee - 원수사 설계사코드 정규화 결과의 수취인 불일치 판정 검증
    // 기존 코드: 실제 sourceAgentCode와 정규화된 agent_id를 읽고도 판정에 사용하지 않음
    // 문제: 다른 설계사의 동일 금액 명세가 MATCHED로 숨겨질 수 있음
    // 개선: 예상 계약 설계사와 실제 원수사코드 매핑 설계사가 다르면 AGENT_MISMATCH로 분류
    @Test
    void 예상과_실제_설계사가_다르면_금액이_같아도_AGENT_MISMATCH다() {
        InsurerGaActualSourceRow actual = actual(21L, "650000", null);
        actual.setActualAgentId(502L);
        actual.setActualAgentMappingCount(1);
        actual.setSourceAgentCode("INSURER-FC-502");
        given(reconciliationMapper.findExpectedSources(MONTH, 3L))
                .willReturn(List.of(expected(11L, 1, "650000", null)));
        given(reconciliationMapper.findActualSources(MONTH, 3L)).willReturn(List.of(actual));

        InsurerGaMatchCandidate result = matcher.match(request()).getFirst();

        assertThat(result.resultType()).isEqualTo(ReconciliationResultType.AGENT_MISMATCH);
        assertThat(result.expectedAgentId()).isEqualTo(501L);
        assertThat(result.actualAgentId()).isEqualTo(502L);
        assertThat(result.actualSourceAgentCode()).isEqualTo("INSURER-FC-502");
    }

    // 2026-08-13 yslee - 예상 설계사 식별값 누락 시 검토필요 회귀 테스트 추가
    // 기존 코드: 실제 설계사가 정상 매핑되면 누락된 예상 설계사와 비교해 AGENT_MISMATCH로 판정
    // 문제: 비교 기준이 없는 상태를 확정 불일치로 분류하여 수동 확인이 필요한 원천 오류를 숨김
    // 개선: expectedAgentId가 없으면 실제 매핑이 정상이어도 REVIEW_REQUIRED로 분류되는지 검증
    @Test
    void 예상_설계사_식별값이_없으면_실제_매핑이_정상이어도_REVIEW_REQUIRED다() {
        InsurerGaExpectedSourceRow expected = expected(11L, 1, "650000", null);
        expected.setExpectedAgentId(null);
        given(reconciliationMapper.findExpectedSources(MONTH, 3L)).willReturn(List.of(expected));
        given(reconciliationMapper.findActualSources(MONTH, 3L))
                .willReturn(List.of(actual(21L, "650000", null)));

        InsurerGaMatchCandidate result = matcher.match(request()).getFirst();

        assertThat(result.resultType()).isEqualTo(ReconciliationResultType.REVIEW_REQUIRED);
        assertThat(result.expectedAgentId()).isNull();
        assertThat(result.actualAgentId()).isEqualTo(501L);
    }

    // 2026-08-20 hjKang - FGC-FUN-048 대사 매칭키를 운영정책서 제36조에 맞춘다
    // 기존 테스트: 같은 월이어도 지급예정일이 하루 다르면 별개 그룹으로 분리된다고 단언했다.
    // 문제: 예상 지급예정일은 계약일 기준(계약일+회차-1개월), 실제 지급예정일은 정산 사이클
    //       기준(정산월 마감 후)이라 실데이터에서는 월조차 다르다(예상 07-10 / 실제 08-25).
    //       이 규칙이 있는 한 MATCHED 는 영구히 0건이 된다.
    // 근거: 제36조 기본 매칭키는 due_month = settlement_month 이며 due_date 는 매칭키가 아니다.
    //       시드명세 §30 도 "매칭 월은 due_month = settlement_month 로 비교한다"로 못 박고 있다.
    // 개선: 같은 월이면 같은 그룹으로 붙는지 검증한다. 1:1 일 때만 붙이는 안전장치는 유지되므로
    //       같은 계약·항목·월에 예상 행이 둘 이상이면 여전히 ambiguous 로 남는다.
    @Test
    void 같은_월이면_지급예정일이_달라도_같은_그룹으로_대사한다() {
        InsurerGaActualSourceRow actual = actual(21L, "650000", null);
        actual.setDueDate(MONTH.plusDays(15));
        given(reconciliationMapper.findExpectedSources(MONTH, 3L))
                .willReturn(List.of(expected(11L, 1, "650000", null)));
        given(reconciliationMapper.findActualSources(MONTH, 3L)).willReturn(List.of(actual));

        List<InsurerGaMatchCandidate> results = matcher.match(request());

        assertThat(results).extracting(InsurerGaMatchCandidate::resultType)
                .containsExactly(ReconciliationResultType.MATCHED);
    }

    @Test
    void 실제_명세의_지급예정일이_없으면_임의로_추정하지_않고_REVIEW_REQUIRED다() {
        InsurerGaActualSourceRow actual = actual(21L, "650000", null);
        actual.setDueDate(null);
        given(reconciliationMapper.findExpectedSources(MONTH, 3L)).willReturn(List.of());
        given(reconciliationMapper.findActualSources(MONTH, 3L)).willReturn(List.of(actual));

        InsurerGaMatchCandidate result = matcher.match(request()).getFirst();

        assertThat(result.resultType()).isEqualTo(ReconciliationResultType.REVIEW_REQUIRED);
        assertThat(result.matchGroupKey()).endsWith(":ENA-A1:NA");
    }

    // 2026-08-14 yslee - FGC-FUN-050 예상 지급예정일 누락 회귀 검증
    // 기존 코드: 예상 지급예정일 null 그룹을 실제 명세와 분리한 뒤 ACTUAL_MISSING으로 확정
    // 문제: 비교 기준이 없는 원천을 누락으로 집계해 수동 검토 대상이 사라짐
    // 개선: null 날짜 예상 원천 후보는 REVIEW_REQUIRED이고 누락 보조 사유를 남기지 않는지 검증
    @Test
    void 예상_지급예정일이_없으면_ACTUAL_MISSING으로_단정하지_않고_REVIEW_REQUIRED다() {
        InsurerGaExpectedSourceRow expected = expected(11L, 1, "650000", null);
        expected.setDueDate(null);
        given(reconciliationMapper.findExpectedSources(MONTH, 3L)).willReturn(List.of(expected));
        given(reconciliationMapper.findActualSources(MONTH, 3L))
                .willReturn(List.of(actual(21L, "650000", null)));

        List<InsurerGaMatchCandidate> results = matcher.match(request());
        InsurerGaMatchCandidate reviewRequired = results.stream()
                .filter(candidate -> candidate.dueDate() == null)
                .findFirst()
                .orElseThrow();

        assertThat(reviewRequired.resultType()).isEqualTo(ReconciliationResultType.REVIEW_REQUIRED);
        assertThat(reviewRequired.secondaryReasonCodes()).isEmpty();
        // 예상·실제가 같은 월이라 한 그룹으로 붙는다. 그 그룹의 예상 지급예정일이 없어
        // 비교 기준이 없으므로 REVIEW_REQUIRED 하나로 수렴한다 — 누락으로 단정하지 않는다.
        assertThat(results).extracting(InsurerGaMatchCandidate::resultType)
                .containsExactly(ReconciliationResultType.REVIEW_REQUIRED);
    }

    // 2026-08-14 yslee - FGC-FUN-050 비영 날짜 정책의 복수 후보 안전성 검증
    // 기존 코드: 허용 범위에 예상일이 둘이면 정렬상 첫 키에 실제 원천을 임의 연결
    // 문제: 비교 기준을 확정할 수 없는 원수사 명세가 MATCHED로 숨겨질 수 있음
    // 개선: 복수 날짜 후보에 걸친 실제 원천은 별도 REVIEW_REQUIRED 후보로 보존
    @Test
    void 날짜_허용범위에_예상일이_둘이면_임의_매칭하지_않고_REVIEW_REQUIRED다() {
        InsurerGaExpectedSourceRow firstExpected = expected(11L, 1, "650000", null);
        InsurerGaExpectedSourceRow secondExpected = expected(12L, 2, "650000", null);
        secondExpected.setDueDate(MONTH.plusDays(16));
        InsurerGaActualSourceRow actual = actual(21L, "650000", null);
        actual.setDueDate(MONTH.plusDays(15));
        given(reconciliationMapper.findExpectedSources(MONTH, 3L))
                .willReturn(List.of(firstExpected, secondExpected));
        given(reconciliationMapper.findActualSources(MONTH, 3L)).willReturn(List.of(actual));
        InsurerGaReconciliationMatcherImpl tolerantMatcher = new InsurerGaReconciliationMatcherImpl(
                reconciliationMapper, oneDayTolerancePolicy());

        List<InsurerGaMatchCandidate> results = tolerantMatcher.match(request());
        InsurerGaMatchCandidate reviewRequired = results.stream()
                .filter(candidate -> MONTH.plusDays(15).equals(candidate.dueDate()))
                .findFirst()
                .orElseThrow();

        assertThat(reviewRequired.resultType()).isEqualTo(ReconciliationResultType.REVIEW_REQUIRED);
        assertThat(reviewRequired.transactionAttributionIds()).containsExactly(21L);
        assertThat(reviewRequired.secondaryReasonCodes()).isEmpty();
        assertThat(results).extracting(InsurerGaMatchCandidate::resultType)
                .doesNotContain(ReconciliationResultType.MATCHED);
    }

    @Test
    void 서로_다른_실제일이_같은_예상일_허용범위에_들면_합산하지_않고_REVIEW_REQUIRED다() {
        InsurerGaExpectedSourceRow expected = expected(11L, 1, "650000", null);
        expected.setDueDate(MONTH.plusDays(15));
        InsurerGaActualSourceRow firstActual = actual(21L, "325000", null);
        firstActual.setDueDate(MONTH.plusDays(14));
        InsurerGaActualSourceRow secondActual = actual(22L, "325000", null);
        secondActual.setDueDate(MONTH.plusDays(16));
        given(reconciliationMapper.findExpectedSources(MONTH, 3L)).willReturn(List.of(expected));
        given(reconciliationMapper.findActualSources(MONTH, 3L))
                .willReturn(List.of(firstActual, secondActual));
        InsurerGaReconciliationMatcherImpl tolerantMatcher = new InsurerGaReconciliationMatcherImpl(
                reconciliationMapper, oneDayTolerancePolicy());

        List<InsurerGaMatchCandidate> reviewCandidates = tolerantMatcher.match(request()).stream()
                .filter(candidate -> candidate.resultType() == ReconciliationResultType.REVIEW_REQUIRED)
                .toList();

        assertThat(reviewCandidates).hasSize(2).allSatisfy(candidate -> {
            assertThat(candidate.transactionAttributionIds()).hasSize(1);
            assertThat(candidate.secondaryReasonCodes()).isEmpty();
        });
        assertThat(reviewCandidates).flatExtracting(InsurerGaMatchCandidate::transactionAttributionIds)
                .containsExactlyInAnyOrder(21L, 22L);
    }

    @Test
    void GA_TO_FC_요청은_FUN_048_02_범위가_아니므로_거절한다() {
        ReconciliationExecutionRequest wrongStage = new ReconciliationExecutionRequest(
                7L, 9L, MONTH, PaymentStage.GA_TO_FC, 3L, 5L);

        assertThatThrownBy(() -> matcher.match(wrongStage))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INSURER_TO_GA");
    }

    private static ReconciliationExecutionRequest request() {
        return new ReconciliationExecutionRequest(7L, 9L, MONTH, PaymentStage.INSURER_TO_GA, 3L, 5L);
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

    private static InsurerGaExpectedSourceRow expected(
            Long scheduleLineId,
            Integer installmentNo,
            String amount,
            Long journalHeaderId
    ) {
        InsurerGaExpectedSourceRow row = new InsurerGaExpectedSourceRow();
        row.setScheduleLineId(scheduleLineId);
        row.setJournalHeaderId(journalHeaderId);
        row.setContractId(100L);
        row.setExpectedAgentId(501L);
        row.setCommissionItemId(200L);
        row.setInstallmentNo(installmentNo);
        row.setDueDate(MONTH.plusDays(14));
        row.setDueMonth(MONTH);
        row.setExpectedAmount(new BigDecimal(amount));
        return row;
    }

    private static InsurerGaActualSourceRow actual(
            Long attributionId,
            String amount,
            Long journalHeaderId
    ) {
        InsurerGaActualSourceRow row = new InsurerGaActualSourceRow();
        row.setTransactionAttributionId(attributionId);
        row.setCommissionTransactionId(attributionId + 1000);
        row.setJournalHeaderId(journalHeaderId);
        row.setContractId(100L);
        row.setActualAgentId(501L);
        row.setActualAgentMappingCount(1);
        row.setCommissionItemId(200L);
        row.setActualInstallmentNo(1);
        row.setSettlementMonth(MONTH);
        row.setDueDate(MONTH.plusDays(14));
        row.setActualAmount(new BigDecimal(amount));
        row.setSourceBusinessKey("INSURER:TEST:" + attributionId);
        row.setSourceAgentCode("INSURER-FC-501");
        return row;
    }
}
