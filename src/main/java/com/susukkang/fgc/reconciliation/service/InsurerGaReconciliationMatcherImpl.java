package com.susukkang.fgc.reconciliation.service;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.util.MoneyUtil;
import com.susukkang.fgc.reconciliation.domain.ReconciliationResultType;
import com.susukkang.fgc.reconciliation.dto.InsurerGaActualSourceRow;
import com.susukkang.fgc.reconciliation.dto.InsurerGaExpectedSourceRow;
import com.susukkang.fgc.reconciliation.dto.InsurerGaMatchCandidate;
import com.susukkang.fgc.reconciliation.dto.ReconciliationMatchSource;
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
    private final TolerancePolicy tolerancePolicy;

    @Override
    @Transactional(readOnly = true)
    public List<InsurerGaMatchCandidate> match(ReconciliationExecutionRequest request) {
        validateRequest(request);

        List<InsurerGaExpectedSourceRow> expectedSources = reconciliationMapper.findExpectedSources(
                request.settlementMonth(), request.insurerId());
        List<InsurerGaActualSourceRow> actualSources = reconciliationMapper.findActualSources(
                request.settlementMonth(), request.insurerId());

        Map<BaseMatchKey, List<InsurerGaExpectedSourceRow>> expectedByKey = groupExpected(expectedSources);
        ActualGroupAlignment actualAlignment = alignActualGroups(
                expectedByKey.keySet(), groupActual(actualSources));
        Map<BaseMatchKey, List<InsurerGaActualSourceRow>> actualByKey = actualAlignment.groups();
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
                                InsurerGaExpectedSourceRow::getExpectedAgentId,
                                actualByKey.getOrDefault(key, List.of()),
                                InsurerGaActualSourceRow::getActualAgentId)
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

    private InsurerGaMatchCandidate createCandidate(
            ReconciliationExecutionRequest request,
            BaseMatchKey key,
            List<InsurerGaExpectedSourceRow> expectedSources,
            List<InsurerGaActualSourceRow> actualSources,
            boolean ambiguousDateResolutionIssue
    ) {
        // 2026-08-13 yslee - 대사 상세행 반올림과 수취인·회차 데이터 품질 판정 적용
        // 기존 코드: numeric 원천값을 그대로 합산하고 회차·수취인 식별값의 누락과 불일치를 판정하지 않음
        // 문제: 행별 HALF_UP 결과와 합계가 달라지거나 미확정 회차·다른 설계사가 정상일치로 처리될 수 있음
        // 개선: 상세행을 원 단위 반올림한 뒤 합산하고 회차 및 정규화된 설계사 식별값을 별도 판정
        BigDecimal expectedTotal = expectedSources.stream()
                .map(InsurerGaExpectedSourceRow::getExpectedAmount)
                .map(MoneyUtil::roundWon)
                .reduce(ZERO, BigDecimal::add);
        BigDecimal actualTotal = actualSources.stream()
                .map(InsurerGaActualSourceRow::getActualAmount)
                .map(MoneyUtil::roundWon)
                .reduce(ZERO, BigDecimal::add);
        boolean hasMissingExpectedInstallment = expectedSources.stream()
                .anyMatch(source -> source.getInstallmentNo() == null);
        boolean hasMissingActualInstallment = actualSources.stream()
                .anyMatch(source -> source.getActualInstallmentNo() == null);
        List<Integer> expectedInstallments = expectedSources.stream()
                .map(InsurerGaExpectedSourceRow::getInstallmentNo)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        List<Integer> actualInstallments = actualSources.stream()
                .map(InsurerGaActualSourceRow::getActualInstallmentNo)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
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
        // 2026-08-13 yslee - 실제 명세 회차의 누락·불일치 판정 분리
        // 기존 코드: 예상 회차만 확인해 모호한 경우 REVIEW_REQUIRED, 실제 회차 불일치 판정 경로는 없음
        // 문제: REC-07의 예상 13회차·실제 14회차가 INSTALLMENT_MISMATCH로 산출되지 않음
        // 개선: 회차 누락·복수값은 REVIEW_REQUIRED, 양쪽 단일 회차가 다르면 INSTALLMENT_MISMATCH로 구분
        boolean installmentResolutionIssue = hasBothSources
                && (hasMissingExpectedInstallment
                || hasMissingActualInstallment
                || expectedInstallments.size() != 1
                || actualInstallments.size() != 1);
        boolean installmentMismatch = hasBothSources
                && !installmentResolutionIssue
                && !tolerancePolicy.matchesInstallment(installmentNo, actualInstallmentNo);
        List<Long> expectedAgentIds = distinctLongs(expectedSources.stream()
                .map(InsurerGaExpectedSourceRow::getExpectedAgentId)
                .toList());
        List<Long> actualAgentIds = distinctLongs(actualSources.stream()
                .map(InsurerGaActualSourceRow::getActualAgentId)
                .toList());
        List<String> actualSourceAgentCodes = distinctStrings(actualSources.stream()
                .map(InsurerGaActualSourceRow::getSourceAgentCode)
                .toList());
        boolean agentResolutionIssue = hasAgentResolutionIssue(expectedSources, actualSources);
        boolean agentMismatch = hasAgentMismatch(
                expectedSources,
                actualSources,
                expectedAgentIds,
                actualAgentIds,
                actualSourceAgentCodes
        );

        // 2026-08-12 yslee - 실제 명세에 회차 컬럼이 없는 기존 정규화 모델의 안전한 매칭 경계 적용
        // 기존 코드: 실제 명세의 회차를 임의 계산하거나 JSON 키에서 읽는 규칙이 없음
        // 문제: 계약월로 회차를 추정하면 납입주기·지급시점에 따라 다른 스케줄행을 정상으로 오인할 수 있음
        // 개선: 문서의 due_month=settlement_month 후보에서 예상 회차가 하나일 때만 회차를 확정하고 그 외에는 검토 대상으로 분류
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

        Long expectedAgentId = singleValue(expectedAgentIds);
        Long actualAgentId = singleValue(actualAgentIds);
        String actualSourceAgentCode = singleValue(actualSourceAgentCodes);

        return new InsurerGaMatchCandidate(
                matchGroupKey(
                        request,
                        key,
                        installmentNo,
                        actualInstallmentNo,
                        expectedAgentId,
                        actualAgentId,
                        actualSourceAgentCode
                ),
                key.contractId(),
                expectedAgentId,
                actualAgentId,
                actualSourceAgentCode,
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
                distinctLongs(expectedSources.stream().map(InsurerGaExpectedSourceRow::getScheduleLineId).toList()),
                distinctLongs(actualSources.stream().map(InsurerGaActualSourceRow::getTransactionAttributionId).toList()),
                distinctLongs(expectedSources.stream().map(InsurerGaExpectedSourceRow::getJournalHeaderId).toList()),
                distinctLongs(actualSources.stream().map(InsurerGaActualSourceRow::getJournalHeaderId).toList()),
                sourceMatches(expectedSources, actualSources)
        );
    }

    // 2026-08-14 hjKang - 결과 저장용 원자행 금액과 원장 추적정보 보존
    // 기존 코드: 후보에 원천 ID 목록과 합계만 있어 reconciliation_match.matched_amount를 재현할 수 없음
    // 문제: 상세 화면과 감사 추적에서 어느 원자행이 얼마를 구성했는지 확인할 수 없음
    // 개선: 예상행과 실제행을 ID 순서로 고정하고 행별 원 단위 금액·원장 ID·역할을 함께 전달
    private static List<ReconciliationMatchSource> sourceMatches(
            List<InsurerGaExpectedSourceRow> expectedSources,
            List<InsurerGaActualSourceRow> actualSources
    ) {
        List<ReconciliationMatchSource> matches = new ArrayList<>();
        expectedSources.stream()
                .sorted(Comparator.comparing(InsurerGaExpectedSourceRow::getScheduleLineId))
                .map(source -> new ReconciliationMatchSource(
                        source.getScheduleLineId(),
                        null,
                        source.getJournalHeaderId(),
                        MoneyUtil.roundWon(source.getExpectedAmount()),
                        "EXPECTED"
                ))
                .forEach(matches::add);
        actualSources.stream()
                .sorted(Comparator.comparing(InsurerGaActualSourceRow::getTransactionAttributionId))
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
            List<InsurerGaExpectedSourceRow> expectedSources,
            List<InsurerGaActualSourceRow> actualSources,
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
        if (installmentResolutionIssue) {
            return ReconciliationResultType.REVIEW_REQUIRED;
        }
        if (agentResolutionIssue) {
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
            List<InsurerGaExpectedSourceRow> expectedSources,
            List<InsurerGaActualSourceRow> actualSources,
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
        // 2026-08-20 hjKang - 보조 사유 DUPLICATE 를 "같은 수취인 안의 복수"로 한정
        // 기존 코드: 양쪽이 존재하고 행이 2개 이상이면 무조건 DUPLICATE 를 보조 사유로 붙였다.
        // 문제: 최종 result_type 은 matcher 반환값이 아니라 ReconciliationReasonClassifier 가
        //       주·보조 사유를 우선순위(DUPLICATE=40 · ACTUAL_MISSING=60 · REVIEW_REQUIRED=110)로
        //       재정렬해 가장 낮은 번호를 고른 결과다. 따라서 보조 사유에 DUPLICATE 가 남아 있으면
        //       classify() 가 REVIEW_REQUIRED 를 돌려줘도 저장은 DUPLICATE·HIGH 로 뒤집힌다.
        //       수취인 짝짓기에서 양쪽에 2명 이상이 남아 대응을 단정할 수 없는 묶음(제36조 5번)이
        //       정확히 이 경우이며, "중복 지급"은 사실과 다르다.
        // 개선: 수취인이 양쪽 모두 하나로 좁혀졌을 때(!agentResolutionIssue)만 복수 행을 중복으로 본다.
        //       그때의 복수는 같은 수취인에게 같은 항목·회차가 두 번 잡힌 진짜 중복이다.
        if (!expectedSources.isEmpty() && !actualSources.isEmpty()
                && !agentResolutionIssue
                && (expectedSources.size() > 1 || actualSources.size() > 1)) {
            reasons.add(ReconciliationResultType.DUPLICATE.name());
        }
        if (agentResolutionIssue) {
            reasons.add(ReconciliationResultType.REVIEW_REQUIRED.name());
        } else if (agentMismatch) {
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

    // 2026-08-14 yslee - FGC-FUN-050 허용오차 정책을 날짜 그룹 정렬에 적용
    // 기존 코드: 예정일을 BaseMatchKey에서 직접 비교해 날짜 정책을 확장할 수 없음
    // 문제: 비영 정책에서 복수 예상일이 허용 범위에 들면 첫 키를 임의 선택해 잘못 일치시킬 수 있음
    // 개선: 날짜 후보가 정확히 하나일 때만 정렬하고 복수 후보 키는 REVIEW_REQUIRED로 전달
    private ActualGroupAlignment alignActualGroups(
            Set<BaseMatchKey> expectedKeys,
            Map<BaseMatchKey, List<InsurerGaActualSourceRow>> actualGroups
    ) {
        List<BaseMatchKey> orderedExpectedKeys = expectedKeys.stream().sorted(BaseMatchKey.ORDER).toList();
        Map<BaseMatchKey, List<InsurerGaActualSourceRow>> aligned = new LinkedHashMap<>();
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
            Integer installmentNo,
            Integer actualInstallmentNo,
            Long expectedAgentId,
            Long actualAgentId,
            String actualSourceAgentCode
    ) {
        return String.join(":",
                PaymentStage.INSURER_TO_GA.name(),
                String.valueOf(request.insurerId()),
                MONTH_FORMATTER.format(key.dueMonth()),
                String.valueOf(key.contractId()),
                String.valueOf(key.commissionItemId()),
                recipientKey(expectedAgentId, actualAgentId, actualSourceAgentCode),
                "E" + Objects.toString(installmentNo, "NA")
                        + "-A" + Objects.toString(actualInstallmentNo, "NA"),
                key.dueDate() == null ? "NA" : DateTimeFormatter.BASIC_ISO_DATE.format(key.dueDate())
        );
    }

    private static List<Long> distinctLongs(List<Long> values) {
        return values.stream().filter(Objects::nonNull).distinct().sorted().toList();
    }

    private static List<String> distinctStrings(List<String> values) {
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }

    private static boolean hasAgentMismatch(
            List<InsurerGaExpectedSourceRow> expectedSources,
            List<InsurerGaActualSourceRow> actualSources,
            List<Long> expectedAgentIds,
            List<Long> actualAgentIds,
            List<String> actualSourceAgentCodes
    ) {
        if (expectedSources.isEmpty() || actualSources.isEmpty()) {
            return false;
        }
        if (expectedAgentIds.isEmpty() && actualAgentIds.isEmpty() && actualSourceAgentCodes.isEmpty()) {
            return false;
        }
        return expectedAgentIds.size() != 1
                || actualAgentIds.size() != 1
                || !expectedAgentIds.getFirst().equals(actualAgentIds.getFirst());
    }

    private static boolean hasAgentResolutionIssue(
            List<InsurerGaExpectedSourceRow> expectedSources,
            List<InsurerGaActualSourceRow> actualSources
    ) {
        // 2026-08-13 yslee - 예상 설계사 식별값 누락 시 검토필요 판정 적용
        // 기존 코드: 실제 원수사 설계사코드의 매핑 실패·중복 여부만 확인
        // 문제: expectedAgentId가 없으면 비교 기준이 없는데도 정상 매핑된 실제 설계사와 AGENT_MISMATCH로 확정됨
        // 개선: 예상·실제 원천이 모두 있을 때 예상 설계사 누락도 식별 불가로 보고 REVIEW_REQUIRED 게이트에 포함
        return !expectedSources.isEmpty()
                && !actualSources.isEmpty()
                && (expectedSources.stream().anyMatch(source -> source.getExpectedAgentId() == null)
                    || actualSources.stream().anyMatch(source ->
                        source.getActualAgentId() == null
                                || !Objects.equals(source.getActualAgentMappingCount(), 1)));
    }

    private static String recipientKey(
            Long expectedAgentId,
            Long actualAgentId,
            String actualSourceAgentCode
    ) {
        return "E" + Objects.toString(expectedAgentId, "NA")
                + "-A" + Objects.toString(actualAgentId, "NA")
                + "-S" + Objects.toString(actualSourceAgentCode, "NA");
    }

    private static <T> T singleValue(List<T> values) {
        return values.size() == 1 ? values.getFirst() : null;
    }

    private record ActualGroupAlignment(
            Map<BaseMatchKey, List<InsurerGaActualSourceRow>> groups,
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
