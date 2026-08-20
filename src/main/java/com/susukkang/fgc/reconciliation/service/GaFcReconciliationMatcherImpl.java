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
    private final TolerancePolicy tolerancePolicy;

    @Override
    @Transactional(readOnly = true)
    public List<GaFcMatchCandidate> match(ReconciliationExecutionRequest request) {
        validateRequest(request);

        List<GaFcExpectedSourceRow> expectedSources = reconciliationMapper.findExpectedSources(
                request.settlementMonth(), request.insurerId());
        List<GaFcActualSourceRow> actualSources = reconciliationMapper.findActualSources(
                request.settlementMonth(), request.insurerId());

        Map<BaseMatchKey, List<GaFcExpectedSourceRow>> expectedByKey = groupExpected(expectedSources);
        ActualGroupAlignment actualAlignment = alignActualGroups(
                expectedByKey.keySet(), groupActual(actualSources));
        Map<BaseMatchKey, List<GaFcActualSourceRow>> actualByKey = actualAlignment.groups();
        Set<BaseMatchKey> keys = new LinkedHashSet<>();
        keys.addAll(expectedByKey.keySet());
        keys.addAll(actualByKey.keySet());

        // 2026-08-19 hjKang - 그룹 안에서 수취인 단위로 짝을 지어 판정한다(운영정책서 제36조).
        // 기존 코드: 그룹 하나를 통째로 합산해 후보 1건을 만들었다.
        // 문제: 관리자수수료처럼 수취인이 여럿인 정상 지급에서 수취인을 하나로 좁히지 못해
        //       REVIEW_REQUIRED 로 빠지고, 팀장 몫이 다른 사람에게 가도 합계가 같으면 통과했다.
        // 개선: RecipientPairing 이 수취인별 짝을 만들고 짝마다 후보를 만든다.
        //       createCandidate 내부 판정은 그대로 두어도 각 짝의 수취인이 단일해 정상 동작한다.
        return keys.stream()
                .sorted(BaseMatchKey.ORDER)
                .flatMap(key -> RecipientPairing.byRecipient(
                                expectedByKey.getOrDefault(key, List.of()),
                                GaFcExpectedSourceRow::getExpectedAgentId,
                                actualByKey.getOrDefault(key, List.of()),
                                GaFcActualSourceRow::getActualAgentId)
                        .stream()
                        .map(pair -> createCandidate(
                                request,
                                key,
                                pair.expected(),
                                pair.actual(),
                                actualAlignment.ambiguousKeys().contains(key)
                        )))
                .toList();
    }

    private GaFcMatchCandidate createCandidate(
            ReconciliationExecutionRequest request,
            BaseMatchKey key,
            List<GaFcExpectedSourceRow> expectedSources,
            List<GaFcActualSourceRow> actualSources,
            boolean ambiguousDateResolutionIssue
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

        // 2026-08-14 yslee - FGC-FUN-050 지급예정일 식별 불가 상태를 누락 판정과 분리
        // 기존 코드: 실제 지급예정일 null만 검토 대상으로 처리하고 예상 지급예정일 null은 누락으로 분리
        // 문제: 비교 기준이 없는 예상 원천이 ACTUAL_MISSING으로 확정되어 대사 집계를 왜곡할 수 있음
        // 개선: 예상·실제 어느 쪽이든 지급예정일이 없으면 REVIEW_REQUIRED 게이트를 우선 적용
        boolean dateResolutionIssue = ambiguousDateResolutionIssue
                || expectedSources.stream().anyMatch(source -> source.getDueDate() == null)
                || actualSources.stream().anyMatch(source -> source.getDueDate() == null);

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
                && !tolerancePolicy.matchesInstallment(installmentNo, actualInstallmentNo);

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
                dateResolutionIssue,
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
                dateResolutionIssue,
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
            boolean dateResolutionIssue,
            boolean installmentResolutionIssue,
            boolean installmentMismatch,
            boolean agentResolutionIssue,
            boolean agentMismatch,
            BigDecimal expectedTotal,
            BigDecimal actualTotal
    ) {
        if (dateResolutionIssue) {
            return ReconciliationResultType.REVIEW_REQUIRED;
        }
        if (installmentResolutionIssue || agentResolutionIssue) {
            return ReconciliationResultType.REVIEW_REQUIRED;
        }
        // 2026-08-19 hjKang - 누락 판정을 중복 판정보다 앞에 둔다.
        // 기존 코드: 행 수가 2 이상이면 한쪽이 비어 있어도 DUPLICATE 로 확정했다.
        // 문제: 관리자수수료 예상 3행에 실제가 0건인 미지급 상태가 "중복 지급"으로 보고돼
        //       사실과 반대되는 문구가 예외함에 남았다.
        // 개선: 한쪽이 비어 있으면 중복일 수 없으므로 누락을 먼저 판정한다.
        if (expectedSources.isEmpty()) {
            return ReconciliationResultType.EXPECTED_MISSING;
        }
        if (actualSources.isEmpty()) {
            return ReconciliationResultType.ACTUAL_MISSING;
        }
        if (expectedSources.size() > 1 || actualSources.size() > 1) {
            return ReconciliationResultType.DUPLICATE;
        }
        if (installmentMismatch) {
            return ReconciliationResultType.INSTALLMENT_MISMATCH;
        }
        if (agentMismatch) {
            return ReconciliationResultType.AGENT_MISMATCH;
        }
        return tolerancePolicy.matchesAmount(expectedTotal, actualTotal)
                ? ReconciliationResultType.MATCHED
                : ReconciliationResultType.AMOUNT_DIFFERENCE;
    }

    private List<String> secondaryReasons(
            ReconciliationResultType primary,
            List<GaFcExpectedSourceRow> expectedSources,
            List<GaFcActualSourceRow> actualSources,
            boolean dateResolutionIssue,
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
        // 한쪽이 비어 있으면 중복이 아니다 — 주 사유와 모순되는 보조 사유를 만들지 않는다.
        if (!expectedSources.isEmpty() && !actualSources.isEmpty()
                && (expectedSources.size() > 1 || actualSources.size() > 1)) {
            reasons.add(ReconciliationResultType.DUPLICATE.name());
        }
        // 2026-08-13 yslee - FGC-FUN-048-03 설계사 식별 불가 보조 사유 보존
        // 기존 코드: 식별 불가 시 주 결과와 같은 REVIEW_REQUIRED를 보조 사유로 추가
        // 문제: 주 결과와 같은 사유를 제거하는 후처리로 설계사 검토 사유가 사라짐
        // 개선: 식별 불가와 명시적 불일치 모두 AGENT_MISMATCH를 보조 사유로 보존
        if (agentResolutionIssue || agentMismatch) {
            reasons.add(ReconciliationResultType.AGENT_MISMATCH.name());
        }
        if (!dateResolutionIssue) {
            if (expectedSources.isEmpty()) {
                reasons.add(ReconciliationResultType.EXPECTED_MISSING.name());
            } else if (actualSources.isEmpty()) {
                reasons.add(ReconciliationResultType.ACTUAL_MISSING.name());
            } else if (!tolerancePolicy.matchesAmount(expectedTotal, actualTotal)) {
                reasons.add(ReconciliationResultType.AMOUNT_DIFFERENCE.name());
            }
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

    // 2026-08-14 yslee - FGC-FUN-050 허용오차 정책을 날짜 그룹 정렬에 적용
    // 기존 코드: 예정일을 BaseMatchKey에서 직접 비교해 날짜 정책을 확장할 수 없음
    // 문제: 비영 정책에서 복수 예상일이 허용 범위에 들면 첫 키를 임의 선택해 잘못 일치시킬 수 있음
    // 개선: 날짜 후보가 정확히 하나일 때만 정렬하고 복수 후보 키는 REVIEW_REQUIRED로 전달
    private ActualGroupAlignment alignActualGroups(
            Set<BaseMatchKey> expectedKeys,
            Map<BaseMatchKey, List<GaFcActualSourceRow>> actualGroups
    ) {
        List<BaseMatchKey> orderedExpectedKeys = expectedKeys.stream().sorted(BaseMatchKey.ORDER).toList();
        Map<BaseMatchKey, List<GaFcActualSourceRow>> aligned = new LinkedHashMap<>();
        Set<BaseMatchKey> ambiguousKeys = new LinkedHashSet<>();
        Map<BaseMatchKey, List<BaseMatchKey>> candidatesByActualKey = new LinkedHashMap<>();
        Map<BaseMatchKey, Integer> uniqueTargetCounts = new LinkedHashMap<>();
        actualGroups.keySet().stream().sorted(BaseMatchKey.ORDER).forEach(actualKey -> {
            // 2026-08-19 hjKang - 후보 선택에서 지급예정일 조건을 제거
            // 기존 코드: 예상 sl.due_date 와 실제 ct.due_date 를 비교해 후보를 걸렀다
            // 문제: 예상 예정일은 계약일 기준(계약일+회차-1개월)이고 실제 예정일은 정산 사이클
            //       기준(정산월 마감 후)이라 월조차 다르다. 2026-07 정산분의 예상 예정일은
            //       2026-07-10, 실제 예정일은 2026-08-25 다. 어떤 정밀도로 비교해도 후보가
            //       0건이 되어 모든 그룹이 예상 전용·실제 전용으로 갈렸고, 회차·설계사·금액
            //       비교는 실행조차 되지 않았다.
            // 개선: 운영정책서 제36조 기본 매칭키(지급단계·보험회사·계약·수수료항목·
            //       due_month = settlement_month)만으로 후보를 고른다. sameDimensions 가
            //       바로 그 조건이다. 1:1 일 때만 붙이는 아래 안전장치는 그대로 두므로,
            //       같은 계약·항목·월에 예상 행이 둘 이상이면 여전히 ambiguous 로 남는다.
            List<BaseMatchKey> candidates = orderedExpectedKeys.stream()
                    .filter(expectedKey -> expectedKey.sameDimensions(actualKey))
                    .toList();
            candidatesByActualKey.put(actualKey, candidates);
            if (candidates.size() == 1) {
                uniqueTargetCounts.merge(candidates.getFirst(), 1, Integer::sum);
            }
        });
        actualGroups.entrySet().stream().sorted(Map.Entry.comparingByKey(BaseMatchKey.ORDER)).forEach(entry -> {
            List<BaseMatchKey> candidates = candidatesByActualKey.get(entry.getKey());
            boolean oneToOne = candidates.size() == 1
                    && uniqueTargetCounts.getOrDefault(candidates.getFirst(), 0) == 1;
            BaseMatchKey alignedKey = oneToOne ? candidates.getFirst() : entry.getKey();
            if (candidates.size() > 1 || (candidates.size() == 1 && !oneToOne)) {
                ambiguousKeys.add(alignedKey);
            }
            aligned.computeIfAbsent(alignedKey, ignored -> new ArrayList<>()).addAll(entry.getValue());
        });
        return new ActualGroupAlignment(aligned, ambiguousKeys);
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

    private record ActualGroupAlignment(
            Map<BaseMatchKey, List<GaFcActualSourceRow>> groups,
            Set<BaseMatchKey> ambiguousKeys
    ) {
    }

    private record BaseMatchKey(Long contractId, Long commissionItemId, LocalDate dueMonth, LocalDate dueDate) {
        private static final Comparator<BaseMatchKey> ORDER = Comparator
                .comparing(BaseMatchKey::contractId)
                .thenComparing(BaseMatchKey::commissionItemId)
                .thenComparing(BaseMatchKey::dueMonth)
                .thenComparing(BaseMatchKey::dueDate, Comparator.nullsLast(Comparator.naturalOrder()));

        private boolean sameDimensions(BaseMatchKey other) {
            return contractId.equals(other.contractId)
                    && commissionItemId.equals(other.commissionItemId)
                    && dueMonth.equals(other.dueMonth);
        }
    }
}
