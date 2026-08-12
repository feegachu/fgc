package com.susukkang.fgc.reconciliation.service;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.reconciliation.domain.ReconciliationResultType;
import com.susukkang.fgc.reconciliation.dto.InsurerGaActualSourceRow;
import com.susukkang.fgc.reconciliation.dto.InsurerGaExpectedSourceRow;
import com.susukkang.fgc.reconciliation.dto.InsurerGaMatchCandidate;
import com.susukkang.fgc.reconciliation.mapper.InsurerGaReconciliationMapper;
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
 * 설명 : FUN-048-02 보험사→GA 예상 스케줄·실제 명세 정규화 매칭
 *
 * @author yslee
 * @since 2026-08-12
 * @version 1.2
 */
@Service
@RequiredArgsConstructor
public class InsurerGaReconciliationMatcherImpl implements InsurerGaReconciliationMatcher {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");

    private final InsurerGaReconciliationMapper reconciliationMapper;
    private final ReconciliationAmountTolerancePolicy tolerancePolicy;

    @Override
    @Transactional(readOnly = true)
    public List<InsurerGaMatchCandidate> match(ReconciliationExecutionRequest request) {
        validateRequest(request);

        List<InsurerGaExpectedSourceRow> expectedSources = reconciliationMapper.findExpectedSources(
                request.settlementMonth(), request.insurerId());
        List<InsurerGaActualSourceRow> actualSources = reconciliationMapper.findActualSources(
                request.settlementMonth(), request.insurerId());

        Map<BaseMatchKey, List<InsurerGaExpectedSourceRow>> expectedByKey = groupExpected(expectedSources);
        Map<BaseMatchKey, List<InsurerGaActualSourceRow>> actualByKey = groupActual(actualSources);
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

    private InsurerGaMatchCandidate createCandidate(
            ReconciliationExecutionRequest request,
            BaseMatchKey key,
            List<InsurerGaExpectedSourceRow> expectedSources,
            List<InsurerGaActualSourceRow> actualSources
    ) {
        BigDecimal expectedTotal = expectedSources.stream()
                .map(InsurerGaExpectedSourceRow::getExpectedAmount)
                .reduce(ZERO, BigDecimal::add);
        BigDecimal actualTotal = actualSources.stream()
                .map(InsurerGaActualSourceRow::getActualAmount)
                .reduce(ZERO, BigDecimal::add);
        List<Integer> installments = expectedSources.stream()
                .map(InsurerGaExpectedSourceRow::getInstallmentNo)
                .distinct()
                .sorted()
                .toList();
        Integer installmentNo = installments.size() == 1 ? installments.getFirst() : null;

        // 2026-08-12 yslee - 실제 명세에 회차 컬럼이 없는 기존 정규화 모델의 안전한 매칭 경계 적용
        // 기존 코드: 실제 명세의 회차를 임의 계산하거나 JSON 키에서 읽는 규칙이 없음
        // 문제: 계약월로 회차를 추정하면 납입주기·지급시점에 따라 다른 스케줄행을 정상으로 오인할 수 있음
        // 개선: 문서의 due_month=settlement_month 후보에서 예상 회차가 하나일 때만 회차를 확정하고 그 외에는 검토 대상으로 분류
        ReconciliationResultType resultType = classify(
                expectedSources,
                actualSources,
                installments,
                expectedTotal,
                actualTotal
        );
        List<String> secondaryReasons = secondaryReasons(
                resultType,
                expectedSources,
                actualSources,
                expectedTotal,
                actualTotal
        );

        return new InsurerGaMatchCandidate(
                matchGroupKey(request, key, installmentNo),
                key.contractId(),
                key.commissionItemId(),
                installmentNo,
                key.dueDate(),
                key.dueMonth(),
                resultType,
                expectedTotal,
                actualTotal,
                actualTotal.subtract(expectedTotal),
                resultType.name(),
                secondaryReasons,
                distinctLongs(expectedSources.stream().map(InsurerGaExpectedSourceRow::getScheduleLineId).toList()),
                distinctLongs(actualSources.stream().map(InsurerGaActualSourceRow::getTransactionAttributionId).toList()),
                distinctLongs(expectedSources.stream().map(InsurerGaExpectedSourceRow::getJournalHeaderId).toList()),
                distinctLongs(actualSources.stream().map(InsurerGaActualSourceRow::getJournalHeaderId).toList())
        );
    }

    private ReconciliationResultType classify(
            List<InsurerGaExpectedSourceRow> expectedSources,
            List<InsurerGaActualSourceRow> actualSources,
            List<Integer> installments,
            BigDecimal expectedTotal,
            BigDecimal actualTotal
    ) {
        if (actualSources.stream().anyMatch(source -> source.getDueDate() == null)) {
            return ReconciliationResultType.REVIEW_REQUIRED;
        }
        if (!expectedSources.isEmpty() && !actualSources.isEmpty() && installments.size() != 1) {
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
        return tolerancePolicy.matches(expectedTotal, actualTotal)
                ? ReconciliationResultType.MATCHED
                : ReconciliationResultType.AMOUNT_DIFFERENCE;
    }

    private List<String> secondaryReasons(
            ReconciliationResultType primary,
            List<InsurerGaExpectedSourceRow> expectedSources,
            List<InsurerGaActualSourceRow> actualSources,
            BigDecimal expectedTotal,
            BigDecimal actualTotal
    ) {
        List<String> reasons = new ArrayList<>();
        if (expectedSources.size() > 1 || actualSources.size() > 1) {
            reasons.add(ReconciliationResultType.DUPLICATE.name());
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

    private static Map<BaseMatchKey, List<InsurerGaExpectedSourceRow>> groupExpected(
            List<InsurerGaExpectedSourceRow> rows
    ) {
        Map<BaseMatchKey, List<InsurerGaExpectedSourceRow>> grouped = new LinkedHashMap<>();
        for (InsurerGaExpectedSourceRow row : rows) {
            requireSource(row.getContractId(), row.getCommissionItemId(), row.getDueMonth(), row.getExpectedAmount());
            grouped.computeIfAbsent(new BaseMatchKey(
                    row.getContractId(), row.getCommissionItemId(), row.getDueMonth(), row.getDueDate()), ignored -> new ArrayList<>())
                    .add(row);
        }
        return grouped;
    }

    private static Map<BaseMatchKey, List<InsurerGaActualSourceRow>> groupActual(
            List<InsurerGaActualSourceRow> rows
    ) {
        Map<BaseMatchKey, List<InsurerGaActualSourceRow>> grouped = new LinkedHashMap<>();
        for (InsurerGaActualSourceRow row : rows) {
            requireSource(row.getContractId(), row.getCommissionItemId(), row.getSettlementMonth(), row.getActualAmount());
            grouped.computeIfAbsent(new BaseMatchKey(
                    row.getContractId(), row.getCommissionItemId(), row.getSettlementMonth(), row.getDueDate()), ignored -> new ArrayList<>())
                    .add(row);
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
        if (request.paymentStage() != PaymentStage.INSURER_TO_GA) {
            throw new IllegalArgumentException("FUN-048-02는 INSURER_TO_GA 실행만 처리합니다.");
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
            Integer installmentNo
    ) {
        return String.join(":",
                PaymentStage.INSURER_TO_GA.name(),
                String.valueOf(request.insurerId()),
                MONTH_FORMATTER.format(key.dueMonth()),
                String.valueOf(key.contractId()),
                String.valueOf(key.commissionItemId()),
                installmentNo == null ? "NA" : String.valueOf(installmentNo),
                key.dueDate() == null ? "NA" : DateTimeFormatter.BASIC_ISO_DATE.format(key.dueDate())
        );
    }

    private static List<Long> distinctLongs(List<Long> values) {
        return values.stream().filter(Objects::nonNull).distinct().sorted().toList();
    }

    private record BaseMatchKey(Long contractId, Long commissionItemId, LocalDate dueMonth, LocalDate dueDate) {
        private static final Comparator<BaseMatchKey> ORDER = Comparator
                .comparing(BaseMatchKey::contractId)
                .thenComparing(BaseMatchKey::commissionItemId)
                .thenComparing(BaseMatchKey::dueMonth)
                .thenComparing(BaseMatchKey::dueDate, Comparator.nullsLast(Comparator.naturalOrder()));
    }
}
