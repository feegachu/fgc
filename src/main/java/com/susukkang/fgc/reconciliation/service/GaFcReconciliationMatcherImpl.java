package com.susukkang.fgc.reconciliation.service;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.util.MoneyUtil;
import com.susukkang.fgc.reconciliation.domain.ReconciliationResultType;
import com.susukkang.fgc.reconciliation.dto.GaFcActualSourceRow;
import com.susukkang.fgc.reconciliation.dto.GaFcExpectedSourceRow;
import com.susukkang.fgc.reconciliation.dto.GaFcMatchCandidate;
import com.susukkang.fgc.reconciliation.dto.ReconciliationMatchSource;
import com.susukkang.fgc.reconciliation.mapper.GaFcReconciliationMapper;
import com.susukkang.fgc.reconciliation.port.ReconciliationExecutionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 설명 : FUN-048-03 GA→FC 예상 스케줄·확정 지급 건 정규화 매칭
 *
 * @author yslee
 * @since 2026-08-13
 * @version 1.2
 */
@Service
@RequiredArgsConstructor
public class GaFcReconciliationMatcherImpl implements GaFcReconciliationMatcher {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");

    private final GaFcReconciliationMapper reconciliationMapper;
    private final ReconciliationAmountTolerancePolicy tolerancePolicy;

    @Override
    @Transactional(readOnly = true)
    public List<GaFcMatchCandidate> match(ReconciliationExecutionRequest request) {
        validateRequest(request);

        List<GaFcExpectedSourceRow> expectedSources = reconciliationMapper.findExpectedSources(
                request.settlementMonth(), request.insurerId());
        List<GaFcActualSourceRow> actualSources = reconciliationMapper.findActualSources(
                request.settlementMonth(), request.insurerId());

        Map<BaseMatchKey, List<GaFcExpectedSourceRow>> expectedByKey = groupExpected(expectedSources);
        Map<BaseMatchKey, List<GaFcActualSourceRow>> actualByKey = groupActual(actualSources);
        Set<BaseMatchKey> keys = new LinkedHashSet<>();
        keys.addAll(expectedByKey.keySet());
        keys.addAll(actualByKey.keySet());

        return keys.stream()
                .sorted(BaseMatchKey.ORDER)
                .map(key -> createCandidate(
                        request,
                        key,
                        expectedByKey.getOrDefault(key, List.of()),
                        actualByKey.getOrDefault(key, List.of())
                ))
                .toList();
    }

    private GaFcMatchCandidate createCandidate(
            ReconciliationExecutionRequest request,
            BaseMatchKey key,
            List<GaFcExpectedSourceRow> expectedSources,
            List<GaFcActualSourceRow> actualSources
    ) {
        // 2026-08-13 yslee - 지급 스케줄·귀속행별 원 단위 반올림 후 합산
        // 기존 코드: GA→FC 예상·실제 지급을 비교하는 계산 경로가 없음
        // 문제: 합계 후 반올림하면 상세행별 HALF_UP을 요구하는 운영정책과 금액 결과가 달라질 수 있음
        // 개선: 예상 스케줄행과 실제 귀속행을 각각 roundWon 처리한 뒤 합산하여 0원 허용오차로 판정
        BigDecimal expectedTotal = expectedSources.stream()
                .map(GaFcExpectedSourceRow::getExpectedAmount)
                .map(MoneyUtil::roundWon)
                .reduce(ZERO, BigDecimal::add);
        BigDecimal actualTotal = actualSources.stream()
                .map(GaFcActualSourceRow::getActualAmount)
                .map(MoneyUtil::roundWon)
                .reduce(ZERO, BigDecimal::add);

        List<Integer> expectedInstallments = distinctIntegers(expectedSources.stream()
                .map(GaFcExpectedSourceRow::getInstallmentNo)
                .toList());
        List<Integer> actualInstallments = distinctIntegers(actualSources.stream()
                .map(GaFcActualSourceRow::getActualInstallmentNo)
                .toList());
        Integer installmentNo = singleValue(expectedInstallments);
        Integer actualInstallmentNo = singleValue(actualInstallments);
        boolean hasBothSources = !expectedSources.isEmpty() && !actualSources.isEmpty();

        // 2026-08-13 yslee - 회차·수취 설계사 비교 불가능 상태와 실제 불일치 상태 분리
        // 기존 코드: 지급 건의 회차·수취인 값이 없을 때 임의 추정하거나 불일치로 단정할 수 있음
        // 문제: 비교 기준이 없는 데이터가 확정 불일치 건수에 포함되면 FUN-048 대사 집계가 왜곡됨
        // 개선: 누락·복수 식별값은 REVIEW_REQUIRED, 양쪽 단일 식별값이 다른 경우에만 명시적 불일치로 판정
        boolean installmentResolutionIssue = hasBothSources
                && (expectedSources.stream().anyMatch(source -> source.getInstallmentNo() == null)
                || actualSources.stream().anyMatch(source -> source.getActualInstallmentNo() == null)
                || expectedInstallments.size() != 1
                || actualInstallments.size() != 1);
        boolean installmentMismatch = hasBothSources
                && !installmentResolutionIssue
                && !Objects.equals(installmentNo, actualInstallmentNo);

        List<Long> expectedAgentIds = distinctLongs(expectedSources.stream()
                .map(GaFcExpectedSourceRow::getExpectedAgentId)
                .toList());
        List<Long> actualAgentIds = distinctLongs(actualSources.stream()
                .map(GaFcActualSourceRow::getActualAgentId)
                .toList());
        Long expectedAgentId = singleValue(expectedAgentIds);
        Long actualAgentId = singleValue(actualAgentIds);
        boolean agentResolutionIssue = hasBothSources
                && (expectedSources.stream().anyMatch(source -> source.getExpectedAgentId() == null)
                || actualSources.stream().anyMatch(source -> source.getActualAgentId() == null)
                || expectedAgentIds.size() != 1
                || actualAgentIds.size() != 1);
        boolean agentMismatch = hasBothSources
                && !agentResolutionIssue
                && !Objects.equals(expectedAgentId, actualAgentId);

        ReconciliationResultType resultType = classify(
                expectedSources,
                actualSources,
                installmentResolutionIssue,
                installmentMismatch,
                agentResolutionIssue,
                agentMismatch,
                expectedTotal,
                actualTotal
        );
        List<String> secondaryReasons = secondaryReasons(
                resultType,
                expectedSources,
                actualSources,
                installmentResolutionIssue,
                installmentMismatch,
                agentResolutionIssue,
                agentMismatch,
                expectedTotal,
                actualTotal
        );

        return new GaFcMatchCandidate(
                matchGroupKey(request, key, installmentNo, actualInstallmentNo, expectedAgentId, actualAgentId),
                key.contractId(),
                expectedAgentId,
                actualAgentId,
                key.commissionItemId(),
                installmentNo,
                actualInstallmentNo,
                key.dueDate(),
                key.dueMonth(),
                resultType,
                expectedTotal,
                actualTotal,
                actualTotal.subtract(expectedTotal),
                resultType.name(),
                secondaryReasons,
                distinctLongs(expectedSources.stream().map(GaFcExpectedSourceRow::getScheduleLineId).toList()),
                distinctLongs(actualSources.stream().map(GaFcActualSourceRow::getTransactionAttributionId).toList()),
                distinctLongs(expectedSources.stream().map(GaFcExpectedSourceRow::getJournalHeaderId).toList()),
                distinctLongs(actualSources.stream().map(GaFcActualSourceRow::getJournalHeaderId).toList()),
                sourceMatches(expectedSources, actualSources)
        );
    }

    // 2026-08-14 hjKang - GA→FC 원자행별 비교금액과 원장 추적정보 보존
    // 기존 코드: 저장 단계가 예상·실제 원천 ID만 받고 각 행의 금액과 분개 ID는 알 수 없음
    // 문제: reconciliation_match와 detail_snapshot만으로 결과 합계를 재검산할 수 없음
    // 개선: 결정적인 원천 ID 순서로 행별 HALF_UP 금액·분개 ID·역할을 전달
    private static List<ReconciliationMatchSource> sourceMatches(
            List<GaFcExpectedSourceRow> expectedSources,
            List<GaFcActualSourceRow> actualSources
    ) {
        List<ReconciliationMatchSource> matches = new ArrayList<>();
        expectedSources.stream()
                .sorted(Comparator.comparing(GaFcExpectedSourceRow::getScheduleLineId))
                .map(source -> new ReconciliationMatchSource(
                        source.getScheduleLineId(),
                        null,
                        source.getJournalHeaderId(),
                        MoneyUtil.roundWon(source.getExpectedAmount()),
                        "EXPECTED"
                ))
                .forEach(matches::add);
        actualSources.stream()
                .sorted(Comparator.comparing(GaFcActualSourceRow::getTransactionAttributionId))
                .map(source -> new ReconciliationMatchSource(
                        null,
                        source.getTransactionAttributionId(),
                        source.getJournalHeaderId(),
                        MoneyUtil.roundWon(source.getActualAmount()),
                        "ACTUAL"
                ))
                .forEach(matches::add);
        return List.copyOf(matches);
    }

    private ReconciliationResultType classify(
            List<GaFcExpectedSourceRow> expectedSources,
            List<GaFcActualSourceRow> actualSources,
            boolean installmentResolutionIssue,
            boolean installmentMismatch,
            boolean agentResolutionIssue,
            boolean agentMismatch,
            BigDecimal expectedTotal,
            BigDecimal actualTotal
    ) {
        if (actualSources.stream().anyMatch(source -> source.getDueDate() == null)) {
            return ReconciliationResultType.REVIEW_REQUIRED;
        }
        if (installmentResolutionIssue || agentResolutionIssue) {
            return ReconciliationResultType.REVIEW_REQUIRED;
        }
        if (expectedSources.size() > 1 || actualSources.size() > 1) {
            return ReconciliationResultType.DUPLICATE;
        }
        if (expectedSources.isEmpty()) {
            return ReconciliationResultType.EXPECTED_MISSING;
        }
        if (actualSources.isEmpty()) {
            return ReconciliationResultType.ACTUAL_MISSING;
        }
        if (installmentMismatch) {
            return ReconciliationResultType.INSTALLMENT_MISMATCH;
        }
        if (agentMismatch) {
            return ReconciliationResultType.AGENT_MISMATCH;
        }
        return tolerancePolicy.matches(expectedTotal, actualTotal)
                ? ReconciliationResultType.MATCHED
                : ReconciliationResultType.AMOUNT_DIFFERENCE;
    }

    private List<String> secondaryReasons(
            ReconciliationResultType primary,
            List<GaFcExpectedSourceRow> expectedSources,
            List<GaFcActualSourceRow> actualSources,
            boolean installmentResolutionIssue,
            boolean installmentMismatch,
            boolean agentResolutionIssue,
            boolean agentMismatch,
            BigDecimal expectedTotal,
            BigDecimal actualTotal
    ) {
        List<String> reasons = new ArrayList<>();
        if (installmentResolutionIssue || installmentMismatch) {
            reasons.add(ReconciliationResultType.INSTALLMENT_MISMATCH.name());
        }
        if (expectedSources.size() > 1 || actualSources.size() > 1) {
            reasons.add(ReconciliationResultType.DUPLICATE.name());
        }
        // 2026-08-13 yslee - FGC-FUN-048-03 설계사 식별 불가 보조 사유 보존
        // 기존 코드: 식별 불가 시 주 결과와 같은 REVIEW_REQUIRED를 보조 사유로 추가
        // 문제: 주 결과와 같은 사유를 제거하는 후처리로 설계사 검토 사유가 사라짐
        // 개선: 식별 불가와 명시적 불일치 모두 AGENT_MISMATCH를 보조 사유로 보존
        if (agentResolutionIssue || agentMismatch) {
            reasons.add(ReconciliationResultType.AGENT_MISMATCH.name());
        }
        if (expectedSources.isEmpty()) {
            reasons.add(ReconciliationResultType.EXPECTED_MISSING.name());
        } else if (actualSources.isEmpty()) {
            reasons.add(ReconciliationResultType.ACTUAL_MISSING.name());
        } else if (!tolerancePolicy.matches(expectedTotal, actualTotal)) {
            reasons.add(ReconciliationResultType.AMOUNT_DIFFERENCE.name());
        }
        return reasons.stream().filter(reason -> !reason.equals(primary.name())).distinct().toList();
    }

    private static Map<BaseMatchKey, List<GaFcExpectedSourceRow>> groupExpected(
            List<GaFcExpectedSourceRow> rows
    ) {
        Map<BaseMatchKey, List<GaFcExpectedSourceRow>> grouped = new LinkedHashMap<>();
        for (GaFcExpectedSourceRow row : rows) {
            requireSource(row.getContractId(), row.getCommissionItemId(), row.getDueMonth(), row.getExpectedAmount());
            grouped.computeIfAbsent(new BaseMatchKey(
                            row.getContractId(), row.getCommissionItemId(), row.getDueMonth(), row.getDueDate()),
                    ignored -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    private static Map<BaseMatchKey, List<GaFcActualSourceRow>> groupActual(
            List<GaFcActualSourceRow> rows
    ) {
        Map<BaseMatchKey, List<GaFcActualSourceRow>> grouped = new LinkedHashMap<>();
        for (GaFcActualSourceRow row : rows) {
            requireSource(row.getContractId(), row.getCommissionItemId(), row.getSettlementMonth(), row.getActualAmount());
            grouped.computeIfAbsent(new BaseMatchKey(
                            row.getContractId(), row.getCommissionItemId(), row.getSettlementMonth(), row.getDueDate()),
                    ignored -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    private static void requireSource(Long contractId, Long commissionItemId, LocalDate month, BigDecimal amount) {
        if (contractId == null || commissionItemId == null || month == null || amount == null) {
            throw new IllegalStateException("대사 원자행의 계약·항목·기준월·금액은 필수입니다.");
        }
    }

    private static void validateRequest(ReconciliationExecutionRequest request) {
        Objects.requireNonNull(request, "대사 실행 요청은 필수입니다.");
        if (request.reconciliationRunId() == null || request.reconciliationRunId() <= 0) {
            throw new IllegalArgumentException("reconciliationRunId는 1 이상이어야 합니다.");
        }
        if (request.paymentStage() != PaymentStage.GA_TO_FC) {
            throw new IllegalArgumentException("FUN-048-03은 GA_TO_FC 실행만 처리합니다.");
        }
        if (request.settlementMonth() == null || request.settlementMonth().getDayOfMonth() != 1) {
            throw new IllegalArgumentException("settlementMonth는 월의 1일이어야 합니다.");
        }
        if (request.insurerId() == null || request.insurerId() <= 0) {
            throw new IllegalArgumentException("insurerId는 1 이상이어야 합니다.");
        }
    }

    private static String matchGroupKey(
            ReconciliationExecutionRequest request,
            BaseMatchKey key,
            Integer installmentNo,
            Integer actualInstallmentNo,
            Long expectedAgentId,
            Long actualAgentId
    ) {
        return String.join(":",
                PaymentStage.GA_TO_FC.name(),
                String.valueOf(request.insurerId()),
                MONTH_FORMATTER.format(key.dueMonth()),
                String.valueOf(key.contractId()),
                String.valueOf(key.commissionItemId()),
                "E" + Objects.toString(expectedAgentId, "NA")
                        + "-A" + Objects.toString(actualAgentId, "NA"),
                "E" + Objects.toString(installmentNo, "NA")
                        + "-A" + Objects.toString(actualInstallmentNo, "NA"),
                key.dueDate() == null ? "NA" : DateTimeFormatter.BASIC_ISO_DATE.format(key.dueDate())
        );
    }

    private static List<Long> distinctLongs(List<Long> values) {
        return values.stream().filter(Objects::nonNull).distinct().sorted().toList();
    }

    private static List<Integer> distinctIntegers(List<Integer> values) {
        return values.stream().filter(Objects::nonNull).distinct().sorted().toList();
    }

    private static <T> T singleValue(List<T> values) {
        return values.size() == 1 ? values.getFirst() : null;
    }

    private record BaseMatchKey(Long contractId, Long commissionItemId, LocalDate dueMonth, LocalDate dueDate) {
        private static final Comparator<BaseMatchKey> ORDER = Comparator
                .comparing(BaseMatchKey::contractId)
                .thenComparing(BaseMatchKey::commissionItemId)
                .thenComparing(BaseMatchKey::dueMonth)
                .thenComparing(BaseMatchKey::dueDate, Comparator.nullsLast(Comparator.naturalOrder()));
    }
}
