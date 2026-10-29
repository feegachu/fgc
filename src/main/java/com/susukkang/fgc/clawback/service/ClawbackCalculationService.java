package com.susukkang.fgc.clawback.service;

import com.susukkang.fgc.clawback.dto.ClawbackCalculationRequest;
import com.susukkang.fgc.clawback.dto.ClawbackCandidateDetailResponse;
import com.susukkang.fgc.clawback.dto.ClawbackCandidateDetailResponse.Calculation;
import com.susukkang.fgc.clawback.dto.ClawbackCandidateDetailResponse.CalculationLine;
import com.susukkang.fgc.clawback.dto.ClawbackCandidateDetailResponse.PaymentEvidence;
import com.susukkang.fgc.clawback.dto.ClawbackCandidateResponse;
import com.susukkang.fgc.clawback.repository.ClawbackCalculationRepository;
import com.susukkang.fgc.clawback.repository.ClawbackCalculationRepository.Rule;
import com.susukkang.fgc.clawback.repository.ClawbackCandidateQueryRepository;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FUN-055 IF-API-85 환수액 계산. D-02 결정(운영정책서 제48조의3)을 적용한다.
 * 기준금액은 확정 지급 귀속행, 환수율은 선택 정책의 clawback_rule, 반올림은 귀속행별 원 단위 HALF_UP이다.
 */
@Service
@RequiredArgsConstructor
public class ClawbackCalculationService {
    /** 산식이 바뀌면 올린다. 같은 원천·정책이어도 산식 버전이 다르면 새 판이 된다. */
    static final String CALC_RULE_VERSION = "claw-calc-v1";
    static final String BASIS_CODE = "PAID_ATTRIBUTED_AMOUNT";
    private static final Set<String> CALCULABLE_CASE_STATUSES = Set.of("DETECTED", "CALCULATED");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final ClawbackCandidateQueryRepository candidates;
    private final ClawbackCalculationRepository store;

    @Transactional
    public Calculation calculate(long candidateId, ClawbackCalculationRequest request, String idempotencyKey,
                                 long userId) {
        if (candidateId < 1) throw invalid("candidateId");
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{1,100}")) {
            throw invalid("Idempotency-Key");
        }
        String reason = request.reason().strip();
        if (reason.isEmpty()) throw invalid("reason");
        String hash = hash(candidateId, request.expectedSourceVersion(), request.policyVersionId(), reason);
        if (!store.tryLockRequest(idempotencyKey)) throw new FgcBusinessException(FgcErrorCode.IMPL_REQUEST_IN_PROGRESS);
        var prior = store.findRequest(idempotencyKey);
        if (prior.isPresent()) {
            var stored = prior.get();
            if (stored.requestedBy() != userId || stored.candidateId() != candidateId
                    || !stored.requestHash().equals(hash)) {
                throw new FgcBusinessException(FgcErrorCode.IMPL_IDEMPOTENCY_CONFLICT);
            }
            return store.findCalculation(stored.calculationId()).orElseThrow().calculation();
        }

        store.lockCandidate(candidateId);
        ClawbackCandidateDetailResponse detail = candidates.findById(candidateId).orElseThrow(() ->
                new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("id", candidateId)));
        ClawbackCandidateResponse candidate = detail.candidate();
        if (!candidate.sourceVersion().equals(request.expectedSourceVersion())) {
            throw new FgcBusinessException(FgcErrorCode.IMPL_STATE_CHANGED, "expectedSourceVersion",
                    Map.of("sourceVersion", candidate.sourceVersion()), null);
        }
        boolean policyOffered = detail.policyCandidates().stream()
                .anyMatch(p -> p.policyVersionId().equals(request.policyVersionId()));
        if (!policyOffered) throw missing("policyVersionId", List.of("POLICY_NOT_OFFERED"));
        if (!candidate.allowedActions().contains("CALCULATE")) throw missing("candidate", candidate.reviewReasons());

        var existing = store.findByInput(candidateId, candidate.sourceVersion(), request.policyVersionId(),
                CALC_RULE_VERSION);
        if (existing.isPresent()) {
            store.saveRequest(idempotencyKey, userId, candidateId, hash, existing.get().calculationId());
            return existing.get().calculation();
        }

        List<PaymentEvidence> paid = detail.paymentAttributions().stream()
                .filter(p -> "CONFIRMED".equals(p.status()) && "PAYMENT".equals(p.cashflowType())).toList();
        if (paid.isEmpty()) throw missing("paymentAttributions", List.of("PAID_ATTRIBUTION_MISSING"));
        int elapsed = detail.elapsedMonths();
        List<Rule> rules = store.findRules(request.policyVersionId()).stream()
                .filter(r -> r.insurerId() == null || r.insurerId().equals(candidate.insurerId()))
                .filter(r -> r.productOfferingId() == null || r.productOfferingId().equals(candidate.productOfferingId()))
                .toList();

        List<CalculationLine> lines = new ArrayList<>();
        List<Map<String, Object>> lineRows = new ArrayList<>();
        List<Map<String, Object>> excluded = new ArrayList<>();
        for (PaymentEvidence payment : paid) {
            List<Rule> forItem = rules.stream()
                    .filter(r -> r.commissionItemId() == null || r.commissionItemId().equals(payment.commissionItemId()))
                    .toList();
            if (forItem.isEmpty()) {
                // 정책이 이 수수료 항목을 환수 대상으로 두지 않았다. 0원 행을 만들지 않고 근거에만 남긴다.
                excluded.add(Map.of("transactionAttributionId", payment.transactionAttributionId(),
                        "commissionItemId", payment.commissionItemId(), "reason", "NO_CLAWBACK_RULE"));
                continue;
            }
            Rule rule = select(forItem, elapsed);
            BigDecimal basis = payment.attributedAmount().setScale(0, RoundingMode.HALF_UP);
            BigDecimal amount = basis.multiply(rule.ratePct()).divide(HUNDRED, 0, RoundingMode.HALF_UP);
            lines.add(new CalculationLine(payment.transactionAttributionId(), payment.commissionItemId(),
                    payment.installmentNo(), elapsed, BASIS_CODE, basis, rule.ratePct(), amount, rule.ruleId()));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("transactionAttributionId", payment.transactionAttributionId());
            row.put("clawbackRuleId", rule.ruleId());
            row.put("basisAmount", basis);
            row.put("appliedRatePct", rule.ratePct());
            row.put("clawbackAmount", amount);
            row.put("commissionItemId", payment.commissionItemId());
            row.put("installmentNo", payment.installmentNo());
            row.put("elapsedMonths", elapsed);
            lineRows.add(row);
        }
        BigDecimal total = lines.stream().map(CalculationLine::clawbackAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        var clawbackCase = store.lockOrCreateCase(candidateId, candidate.contractId(),
                "STATUS_EVENT".equals(candidate.triggerType().name()) ? candidate.sourceId() : null,
                request.policyVersionId(), userId);
        if (!CALCULABLE_CASE_STATUSES.contains(clawbackCase.status())) {
            // 통지·확정·회수로 넘어간 case는 다시 계산하지 않는다(이번 범위는 DETECTED/CALCULATED).
            throw new FgcBusinessException(FgcErrorCode.IMPL_STATE_CHANGED, "candidate",
                    Map.of("caseStatus", clawbackCase.status()), null);
        }
        int versionNo = store.nextVersion(clawbackCase.caseId());
        OffsetDateTime calculatedAt = OffsetDateTime.now(SEOUL).truncatedTo(ChronoUnit.MICROS);
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("calcRuleVersion", CALC_RULE_VERSION);
        basis.put("sourceVersion", candidate.sourceVersion());
        basis.put("triggerType", candidate.triggerType().name());
        basis.put("contractDate", candidate.contractDate().toString());
        basis.put("eventDate", candidate.eventDate().toString());
        basis.put("elapsedMonths", elapsed);
        basis.put("elapsedMonthRule", "CONTRACT_DATE_TO_EVENT_DATE_COMPLETED_MONTHS");
        basis.put("basisCode", BASIS_CODE);
        basis.put("rounding", "LINE_HALF_UP_WON");
        basis.put("policyVersionId", request.policyVersionId());
        basis.put("reason", reason);
        basis.put("excluded", excluded);
        Calculation calculation = new Calculation(clawbackCase.caseId(), candidateId, versionNo,
                candidate.sourceVersion(), request.policyVersionId(), calculatedAt, userId, total, lines, basis);
        long calculationId = store.insertCalculation(clawbackCase.caseId(), candidateId, versionNo,
                candidate.sourceVersion(), request.policyVersionId(), CALC_RULE_VERSION, elapsed, total, reason,
                calculation, userId, calculatedAt);
        store.replaceLines(clawbackCase.caseId(), lineRows);
        store.markCalculated(clawbackCase.caseId(), request.policyVersionId(), total);
        store.saveRequest(idempotencyKey, userId, candidateId, hash, calculationId);
        // 재요청·수렴 응답과 같은 값이 되도록 저장된 snapshot을 그대로 돌려준다.
        return store.findCalculation(calculationId).orElseThrow().calculation();
    }

    /**
     * 경과월 구간에 맞는 가장 구체적인 규칙(항목 > 판매버전 > 보험회사 지정 순). 구간이 비거나 같은 구체성의
     * 규칙이 둘 이상이면 임의로 고르지 않고 422로 멈춘다. 허용하지 않는 기준·조건식도 같다.
     */
    private static Rule select(List<Rule> forItem, int elapsed) {
        List<Rule> inBand = forItem.stream()
                .filter(r -> r.monthFrom() <= elapsed && elapsed <= r.monthTo())
                .sorted(Comparator.comparingInt(ClawbackCalculationService::specificity).reversed()).toList();
        if (inBand.isEmpty()) throw missing("clawbackRule", List.of("ELAPSED_MONTH_NOT_COVERED"));
        if (inBand.size() > 1 && specificity(inBand.get(0)) == specificity(inBand.get(1))) {
            throw missing("clawbackRule", List.of("CLAWBACK_RULE_AMBIGUOUS"));
        }
        Rule rule = inBand.get(0);
        if (!BASIS_CODE.equals(rule.basisCode())) throw missing("clawbackRule", List.of("BASIS_CODE_UNSUPPORTED"));
        if (rule.condition() != null && !rule.condition().replaceAll("\\s", "").equals("{}")) {
            throw missing("clawbackRule", List.of("CONDITION_UNSUPPORTED"));
        }
        return rule;
    }

    private static int specificity(Rule rule) {
        return (rule.commissionItemId() != null ? 4 : 0) + (rule.productOfferingId() != null ? 2 : 0)
                + (rule.insurerId() != null ? 1 : 0);
    }

    private static String hash(long candidateId, String sourceVersion, long policyVersionId, String reason) {
        try {
            String canonical = candidateId + "\n" + sourceVersion + "\n" + policyVersionId + "\n" + reason;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("환수 계산 요청을 해시할 수 없습니다.", e);
        }
    }

    private static FgcBusinessException missing(String field, List<String> reasons) {
        return new FgcBusinessException(FgcErrorCode.IMPL_POLICY_MISSING, field,
                Map.of("field", field, "reasons", reasons), null);
    }

    private static FgcBusinessException invalid(String field) {
        return new FgcBusinessException(FgcErrorCode.COMMON_002, field, Map.of("field", field), null);
    }
}
