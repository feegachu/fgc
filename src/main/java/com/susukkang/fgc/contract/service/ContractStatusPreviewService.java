package com.susukkang.fgc.contract.service;

import com.susukkang.fgc.clawback.dto.ClawbackCandidateDetailResponse;
import com.susukkang.fgc.clawback.repository.ClawbackCandidateQueryRepository;
import com.susukkang.fgc.clawback.repository.ClawbackCandidateWriteRepository;
import com.susukkang.fgc.clawback.service.ClawbackCalculationService;
import com.susukkang.fgc.common.code.ContractStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.contract.dto.ContractStatusEffect;
import com.susukkang.fgc.contract.dto.ContractStatusEffect.EntityType;
import com.susukkang.fgc.contract.dto.ContractStatusPreviewRequest;
import com.susukkang.fgc.contract.dto.ContractStatusPreviewResponse;
import com.susukkang.fgc.contract.dto.ContractStatusPreviewResponse.ClawbackLine;
import com.susukkang.fgc.contract.dto.ContractStatusPreviewResponse.ClawbackReview;
import com.susukkang.fgc.contract.dto.ContractStatusPreviewResponse.HeldAmount;
import com.susukkang.fgc.contract.dto.ContractStatusPreviewResponse.PaidAmount;
import com.susukkang.fgc.contract.dto.ContractStatusPreviewResponse.PreviewLine;
import com.susukkang.fgc.contract.dto.ContractStatusPreviewResponse.PreviewPayment;
import com.susukkang.fgc.contract.entity.ContractStatusEvent;
import com.susukkang.fgc.contract.entity.InsuranceContract;
import com.susukkang.fgc.contract.repository.ContractStatusEventRepository;
import com.susukkang.fgc.contract.repository.InsuranceContractRepository;
import com.susukkang.fgc.contract.repository.PaymentHoldRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 설명 : FUN-077 상태 사건 영향 미리보기(IF-API-64). 저장하지 않은 사건을 후속 처리기의 {@code plan}과 환수 계산의 쓰기 없는 계산부에
 * 넣어 변경 전후를 비교한다. 처리기가 실제 처리 때 쓰는 입력·규칙을 그대로 쓰므로 같은 스냅샷이면 저장 뒤 결과와 같다.
 *
 * <p>쓰기 서비스를 실행하고 되돌리지 않는다. 읽기 전용 트랜잭션이라 DB가 쓰기를 거절하고, 사건 엔티티는 영속화하지 않는다.
 * 하나의 REPEATABLE READ 스냅샷으로 모든 입력을 읽는다. 실제 저장은 IF-API-19 로만 하며 그때 최신 상태로 다시 검증한다.
 *
 * @version 1.0
 * @since 2026-11-04
 */
@Service
@RequiredArgsConstructor
public class ContractStatusPreviewService {

    private static final String FIELD_EVENT_TYPE = "eventType";
    private static final String FIELD_EFFECTIVE_AT = "effectiveAt";

    private final InsuranceContractRepository contracts;
    private final ContractStatusEventRepository events;
    private final ObjectProvider<ContractStatusFollowUpProcessor> processors;
    private final PaymentHoldRepository holds;
    private final ClawbackCandidateQueryRepository clawbackEvidence;
    private final ClawbackCalculationService clawback;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ContractStatusPreviewResponse preview(Long contractId, ContractStatusPreviewRequest request) {
        InsuranceContract contract = contracts.findById(contractId)
                .orElseThrow(() -> new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("id", contractId)));
        ContractStatus current = contract.getCurrentStatus();
        // 저장(IF-API-19)과 같은 전이 표·일자 범위. 미리보기에는 접수일시가 없으므로 효력일시가 현재를 넘는지만 본다.
        ContractServiceImpl.requireAllowedTransition(current, request.eventType(), FIELD_EVENT_TYPE);
        ContractServiceImpl.requireLatestEffectiveAt(events, contractId, contract.getContractDate(),
                request.effectiveAt(), FIELD_EFFECTIVE_AT);
        OffsetDateTime snapshotAt = DateUtil.nowSeoul();
        if (request.effectiveAt().isAfter(snapshotAt)) {
            throw ContractServiceImpl.eventOrder(FIELD_EFFECTIVE_AT, "효력일시는 현재 시각보다 늦을 수 없습니다.");
        }
        ContractStatusEvent event = unsavedEvent(contractId, current, request);

        Set<Long> heldBefore = holds.openHeldPaymentIds(contractId);
        Set<Long> heldAfter = new HashSet<>(heldBefore);
        List<ContractStatusEffect> effects = new ArrayList<>();
        processors.orderedStream().filter(processor -> processor.supports(event)).forEach(processor -> {
            for (ContractStatusEffect effect : processor.plan(event)) {
                effects.add(effect);
                if (effect.entityType() == EntityType.PAYMENT && "HELD".equals(effect.nextState())) {
                    heldAfter.add(effect.entityId());
                } else if (effect.entityType() == EntityType.PAYMENT && "RELEASED".equals(effect.nextState())
                        && !holds.hasOtherOpenHold(effect.entityId(), contractId, processor.releasedHoldReasons())) {
                    heldAfter.remove(effect.entityId());
                }
            }
        });

        Map<Long, BigDecimal> draftAmounts = draftAmounts(contractId);
        LocalDate eventDate = request.effectiveAt().atZoneSameInstant(DateUtil.SEOUL_ZONE).toLocalDate();
        // 후보가 되지 않는 사건도 기지급 근거는 같은 SQL에서 읽는다(지급 귀속 범위가 어긋나면 null).
        ClawbackCandidateDetailResponse evidence =
                clawbackEvidence.findUnsavedStatusEvent(contractId, eventDate).orElseThrow();
        Map<Long, LineFact> lineFacts = lineFacts(contractId);

        return new ContractStatusPreviewResponse(
                contractId, current, request.eventType(), request.effectiveAt(),
                lines(effects, event, lineFacts, next -> "HOLD".equals(next) || "CANCELLED".equals(next)),
                lines(effects, event, lineFacts, "PLANNED"::equals),
                lines(effects, event, lineFacts, "EXCLUDED"::equals),
                effects.stream().filter(effect -> effect.entityType() == EntityType.PAYMENT)
                        .map(effect -> new PreviewPayment(effect.entityId(), effect.amount(), effect.previousState(),
                                effect.nextState(), effect.reasonCode(), effect.reason()))
                        .toList(),
                new HeldAmount(sum(heldBefore, draftAmounts), sum(heldAfter, draftAmounts)),
                List.of(new PaidAmount(PaymentStage.INSURER_TO_GA, paidToGa(contractId)),
                        new PaidAmount(PaymentStage.GA_TO_FC, evidence.candidate().paidAmount())),
                clawbackReview(request.eventType(), evidence),
                pendingFollowUps(contractId),
                snapshotAt);
    }

    /** IF-API-19 가 저장할 사건과 같은 순번·직전 상태를 가진, 저장하지 않는 사건. */
    private ContractStatusEvent unsavedEvent(Long contractId, ContractStatus current, ContractStatusPreviewRequest request) {
        return ContractStatusEvent.builder()
                .contractId(contractId)
                .eventSeq(events.findMaxEventSeq(contractId) + 1)
                .previousStatus(current)
                .newStatus(request.eventType())
                .effectiveAt(request.effectiveAt())
                .build();
    }

    private static List<PreviewLine> lines(List<ContractStatusEffect> effects, ContractStatusEvent event,
                                    Map<Long, LineFact> facts, Predicate<String> nextState) {
        return effects.stream()
                .filter(effect -> effect.entityType() == EntityType.SCHEDULE_LINE && nextState.test(effect.nextState()))
                .map(effect -> {
                    LineFact fact = facts.get(effect.entityId());
                    return new PreviewLine(effect.entityId(), fact.stage(), fact.itemName(), fact.installmentNo(), fact.dueDate(),
                            PaymentHoldFollowUpProcessor.resumedDueDate(event, effect).orElse(null), effect.amount(),
                            effect.previousState(), effect.nextState(), effect.reasonCode(), effect.reason());
                })
                .toList();
    }

    private record LineFact(PaymentStage stage, String itemName, Integer installmentNo, LocalDate dueDate) {
    }

    private Map<Long, LineFact> lineFacts(Long contractId) {
        Map<Long, LineFact> facts = new HashMap<>();
        jdbc.query("""
                SELECT sl.schedule_line_id, sh.payment_stage, ci.item_name, sl.installment_no, sl.due_date
                  FROM fgc.schedule_header sh
                  JOIN fgc.schedule_line sl ON sl.schedule_header_id = sh.schedule_header_id
                  JOIN fgc.commission_item ci ON ci.commission_item_id = sl.commission_item_id
                 WHERE sh.contract_id = ? AND sh.schedule_purpose = 'OPERATIONAL' AND sh.active_yn
                """, rs -> {
            facts.put(rs.getLong("schedule_line_id"), new LineFact(PaymentStage.valueOf(rs.getString("payment_stage")),
                    rs.getString("item_name"), rs.getObject("installment_no", Integer.class),
                    rs.getObject("due_date", LocalDate.class)));
        }, contractId);
        return facts;
    }

    /** 미확정 지급 건의 이 계약 귀속액. 귀속행을 원 단위로 반올림한 뒤 더한다(운영정책서 제17조의2, 기지급과 같은 기준). */
    private Map<Long, BigDecimal> draftAmounts(Long contractId) {
        Map<Long, BigDecimal> amounts = new HashMap<>();
        jdbc.query("""
                SELECT ta.commission_transaction_id, SUM(ROUND(ta.attributed_amount, 0))
                  FROM fgc.transaction_attribution ta
                  JOIN fgc.commission_transaction ct ON ct.commission_transaction_id = ta.commission_transaction_id
                 WHERE ta.contract_id = ? AND ta.attribution_scope = 'CONTRACT' AND ct.cashflow_type = 'PAYMENT'
                   AND ct.status = 'DRAFT'
                 GROUP BY ta.commission_transaction_id
                """, rs -> {
            amounts.put(rs.getLong(1), rs.getBigDecimal(2));
        }, contractId);
        return amounts;
    }

    private static BigDecimal sum(Set<Long> paymentIds, Map<Long, BigDecimal> draftAmounts) {
        return paymentIds.stream().map(draftAmounts::get).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** GA→설계사 기지급은 환수 근거 SQL의 값을 쓰고, 원수사→GA 기지급만 같은 조건으로 따로 읽는다. */
    private BigDecimal paidToGa(Long contractId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(ROUND(ta.attributed_amount, 0)), 0)
                  FROM fgc.transaction_attribution ta
                  JOIN fgc.commission_transaction ct ON ct.commission_transaction_id = ta.commission_transaction_id
                 WHERE ta.contract_id = ? AND ta.attribution_scope = 'CONTRACT' AND ct.payment_stage = 'INSURER_TO_GA'
                   AND ct.status = 'CONFIRMED' AND ct.cashflow_type = 'PAYMENT'
                """, BigDecimal.class, contractId);
    }

    private long pendingFollowUps(Long contractId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM fgc.contract_event_job j
                  JOIN fgc.contract_status_event e ON e.contract_status_event_id = j.contract_status_event_id
                 WHERE e.contract_id = ? AND j.state <> 'SUCCEEDED'
                """, Long.class, contractId);
    }

    private ClawbackReview clawbackReview(ContractStatus eventType, ClawbackCandidateDetailResponse evidence) {
        if (!ClawbackCandidateWriteRepository.STATUS_EVENT_TRIGGERS.contains(eventType)) {
            return new ClawbackReview(false, null, "이 사건은 환수 검토 후보가 되지 않습니다.", List.of(), null, null, null, null, List.of());
        }
        ClawbackCalculationService.Estimate estimate = clawback.estimate(evidence);
        Map<Long, String> itemNames = new HashMap<>();
        jdbc.query("SELECT commission_item_id, item_name FROM fgc.commission_item",
                rs -> {
                    itemNames.put(rs.getLong(1), rs.getString(2));
                });
        List<ClawbackLine> lines = estimate.lines().stream()
                .map(line -> new ClawbackLine(line.transactionAttributionId(), itemNames.get(line.commissionItemId()),
                        line.installmentNo(), line.basisAmount(), line.appliedRatePct(), line.clawbackAmount()))
                .toList();
        ClawbackCandidateDetailResponse.PolicyCandidate policy = estimate.policy();
        return new ClawbackReview(true, estimate.amount(), clawbackReason(estimate), estimate.reasons(),
                evidence.elapsedMonths(), policy == null ? null : policy.policyVersionId(),
                policy == null ? null : policy.policyCode(), policy == null ? null : policy.versionNo(), lines);
    }

    /** 제48조의2 6항: 회사 약정(환수 정책)이 없으면 계산하지 않고 근거 없음으로 표시한다. 예상액은 확정액이 아니다. */
    private static String clawbackReason(ClawbackCalculationService.Estimate estimate) {
        if (estimate.amount() != null) {
            return "환수 정책으로 계산한 예상액입니다. 확정액이 아니며, 저장 뒤 환수 화면에서 정책을 확인해 계산합니다.";
        }
        if (estimate.reasons().contains("POLICY_MISSING")) {
            return "근거 없음(회사 약정 필요). 적용할 환수 정책이 없어 계산하지 않았습니다.";
        }
        return "근거가 부족해 계산하지 않았습니다.";
    }
}
