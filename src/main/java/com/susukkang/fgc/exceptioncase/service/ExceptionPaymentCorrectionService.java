package com.susukkang.fgc.exceptioncase.service;

import com.susukkang.fgc.audit.service.AuditLogService;
import com.susukkang.fgc.cap.dto.CapExceptionResolveCommand;
import com.susukkang.fgc.cap.service.CapExceptionService;
import com.susukkang.fgc.clawback.service.ClawbackCandidateQueryService;
import com.susukkang.fgc.common.code.*;
import com.susukkang.fgc.common.exception.*;
import com.susukkang.fgc.exceptioncase.dto.*;
import com.susukkang.fgc.exceptioncase.repository.*;
import com.susukkang.fgc.journal.dto.*;
import com.susukkang.fgc.journal.service.JournalCorrectionService;
import com.susukkang.fgc.journal.service.JournalPersistenceService;
import com.susukkang.fgc.journal.repository.JournalHeaderRepository;
import com.susukkang.fgc.journal.domain.JournalType;
import com.susukkang.fgc.journal.domain.JournalAccountCode;
import com.susukkang.fgc.reconciliation.service.PaymentCorrectionReconciliationService;
import com.susukkang.fgc.transaction.dto.*;
import com.susukkang.fgc.transaction.repository.PaymentJournalCorrectionRepository;
import com.susukkang.fgc.transaction.service.CommissionPaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;

/** 지급 원천 수정·확정 게이트·재대사·예외 이력을 같은 트랜잭션에서 처리한다. */
@Service
@RequiredArgsConstructor
public class ExceptionPaymentCorrectionService {
    private static final Set<String> CAP_TYPES = Set.of("CAP_WARNING", "CAP_VIOLATION", "CAP_REVIEW_REQUIRED");
    private final ExceptionPaymentRepository repository;
    private final ExceptionCaseRepository cases;
    private final ExceptionCaseQueryRepository queries;
    private final ExceptionCaseService exceptions;
    private final CommissionPaymentService payments;
    private final PaymentJournalCorrectionRepository paymentWrites;
    private final JournalCorrectionService journals;
    private final PaymentCorrectionReconciliationService reconciliation;
    private final AuditLogService audit;
    private final ClawbackCandidateQueryService clawbacks;
    private final JournalPersistenceService journalPersistence;
    private final JournalHeaderRepository journalHeaders;
    private final com.susukkang.fgc.cap.service.CapCheckService capChecks;
    private final CapExceptionService capExceptions;

    @Transactional
    public ExceptionPaymentContext context(Long id, Long actor) {
        var target = cases.findByIdForUpdate(id);
        requireTarget(target, actor);
        var source = repository.source(id);
        requireSupported(source);
        var scope = repository.capScope(id);
        var reco = repository.reconciliationSource(source);
        String correctionTarget = correctionTarget(scope, reco);
        String contractLink = source.contractId() == null ? null : "/contracts/" + source.contractId();
        String scheduleLink = scope != null && scope.scheduleHeaderId() != null ? "/schedules/" + scope.scheduleHeaderId()
                : contractLink == null ? null : contractLink + "?tab=schedules";
        var paymentIds = Set.of("PAYMENT", "RECOVERY").contains(correctionTarget) ? repository.paymentIds(id) : List.<Long>of();
        Map<Long, Long> paymentJournalIds = new LinkedHashMap<>();
        for (Long paymentId : paymentIds) {
            Long journalId = repository.postedJournal(paymentId);
            if (journalId != null) paymentJournalIds.put(paymentId, journalId);
        }
        return new ExceptionPaymentContext(paymentIds.stream().map(payments::get).toList(),
                repository.suggestedPayment(source), scope == null ? null : capChecks.findDetail(scope.checkId()).orElse(null),
                scope != null && scope.scheduledBasis(), scheduleLink,
                scope == null || scope.policyVersionId() == null ? null : "/policies?tab=management&policyVersionId=" + scope.policyVersionId() + "&asOf=" + scope.asOfDate(),
                correctionTarget, scope != null ? scope.stage() : reco == null ? null : reco.stage(), contractLink, repository.remainingIssues(source),
                reco == null ? null : reco.resultType(), reco != null && "ACTUAL_MISSING".equals(reco.resultType()) && !repository.hasRegisteredActual(reco), repository.deferrableFrom(source.contractId()), paymentJournalIds);
    }

    @Transactional
    public TransactionPrecheckResponse preview(Long id, ExceptionPaymentCorrectionRequest request, Long actor) {
        requireTarget(cases.findByIdForUpdate(id), actor);
        var source = repository.source(id);
        requireSupported(source);
        requirePaymentBasis(id);
        if (request.paymentId() != null && !repository.paymentIds(id).contains(request.paymentId())) throw invalid("paymentId");
        requireCorrectionInput(source, request);
        var result = payments.precheckCorrectionCandidate(request.payment(), request.paymentId());
        var blockers = result.blockers().stream().filter(b -> !CAP_TYPES.contains(source.type()) || !"FGC-CAP-003".equals(b.code())).toList();
        return new TransactionPrecheckResponse(result.paymentId(), result.capPreview(), blockers, blockers.isEmpty(), result.capExclusions());
    }

    @Transactional
    public ExceptionPaymentCorrectionResponse correct(Long id, ExceptionPaymentCorrectionRequest request, Long actor, String login) {
        var source = repository.source(id);
        requireSupported(source);
        requirePaymentBasis(id);
        repository.lockValidations(id);
        var runIds = repository.reconciliationRuns(source);
        var paymentId = request.paymentId();
        if (paymentId != null && !repository.paymentIds(id).contains(paymentId)) throw invalid("paymentId");
        requireCorrectionInput(source, request);

        if (paymentId != null) {
            // 월 검증 확정·지급·예외 순서로 잠가 지급 초안 삭제와 충돌하지 않는다.
            var hint = payments.get(paymentId);
            var header = new JournalCorrectionHeaderRow();
            header.setSourceEntityId(paymentId.toString());
            header.setContractId(hint.contractId());
            header.setValidationRunId(source.validationRunId());
            boolean capWithoutJournal = CAP_TYPES.contains(source.type()) && repository.postedJournal(paymentId) == null;
            // 정정 전후 월·단계·보험회사 범위를 함께 정렬해서 잠그고 두 범위 모두 재대사한다.
            var affectedRuns = capWithoutJournal ? reconciliation.lockAffectedRuns(header, request.payment())
                    : reconciliation.lockAffectedRuns(header);
            // 지급 한도 예외는 검증 실행 ID가 없더라도 같은 지급의 월 대사에 영향을 준다.
            if (capWithoutJournal) {
                runIds = affectedRuns;
            }
            if (!paymentWrites.lockPayment(paymentId)) throw invalid("paymentId");
        }
        var target = cases.findByIdForUpdate(id);
        requireTarget(target, actor);
        // 배정이나 원천이 바뀐 뒤의 요청을 이전 화면의 정보로 처리하지 않는다.
        if (!repository.source(id).equals(source)) throw invalid("exceptionCaseId");
        CommissionPaymentResponse old = paymentId == null ? null : payments.get(paymentId);
        if (old != null && (request.expectedUpdatedAt() == null
                || !request.expectedUpdatedAt().toInstant().equals(old.updatedAt().toInstant()))) {
            throw new FgcBusinessException(FgcErrorCode.TRAN_010);
        }
        var reco = repository.reconciliationSource(source);
        var type = "DEFER".equals(request.plannedAction()) ? ExceptionActionType.DEFER
                : reco != null && "ACTUAL_MISSING".equals(reco.resultType()) ? ExceptionActionType.RECONCILE_AGAIN
                : "RECONCILIATION_MISMATCH".equals(source.type()) ? ExceptionActionType.CORRECT : inferAction(old, request.payment());
        String reason = correctionReason(type, old, request.payment());
        if ("DEFER".equals(request.plannedAction())) reason += " 초과분 " + request.deferredAmount() + "원 이연 예정일 " + request.deferredPaymentDate();
        if (reco != null && "AMOUNT_DIFFERENCE".equals(reco.resultType())) {
            reason += " " + ("SETTLEMENT_COMPLETED".equals(request.correctionMode())
                    ? "추가 송금 및 반환 확인: " + request.settlementEvidence().trim() : "기록 오류 정정");
        }
        var body = normalized(id, old, request.payment(), reason);
        CommissionPaymentResponse corrected;
        Long journal = old == null ? null : repository.postedJournal(old.paymentId());
        if (journal != null) {
            var result = journals.reverseAndRepost(new JournalRepostCommand(journal, reason, body.evidenceRef(), actor,
                    body.scheduledPaymentDate(), reason, repository.repostLines(journal, body.amount(), reason), body));
            corrected = payments.get(repository.replacement(result.correctionGroupKey()));
        } else {
            if (old != null && old.status() == CommissionPaymentStatus.DRAFT) {
                corrected = payments.update(old.paymentId(), new CommissionPaymentUpdateRequest(body.sourceType(),
                        body.sourceBusinessKey(), body.contractId(), body.agentId(), body.commissionItemId(), body.amount(),
                        body.settlementMonth(), body.cashflowType(), body.scheduledPaymentDate(), body.paymentStage(),
                        body.allocationPolicyVersion(), body.attributions(), body.evidenceRef(), body.note(), request.expectedUpdatedAt()));
                if (reco != null && "ACTUAL_MISSING".equals(reco.resultType())) repository.bindMissingActual(corrected.paymentId(), source);
            } else {
                if (old != null) {
                    if (old.status() != CommissionPaymentStatus.CONFIRMED || paymentWrites.cancel(old.paymentId()) != 1) throw invalid("paymentId");
                    audit.record(AuditLogService.AuditEvent.builder().actionCode("PAYMENT_CANCELLED")
                            .entityType("COMMISSION_TRANSACTION").entityId(old.paymentId().toString()).userId(actor)
                            .before(old).after(Map.of("status", "CANCELLED", "exceptionCaseId", id)).reason(reason).build());
                }
                corrected = payments.create(body);
                if (old == null) repository.bindMissingActual(corrected.paymentId(), source);
                if (old != null) {
                    paymentWrites.copySourceMetadata(old.paymentId(), corrected.paymentId());
                    paymentWrites.correctPaymentDate(corrected.paymentId(), body.scheduledPaymentDate());
                }
            }
            var preview = payments.precheckCorrectionCandidate(body, corrected.paymentId());
            boolean cap = CAP_TYPES.contains(source.type());
            if (preview.blockers().stream().anyMatch(b -> !cap || !"FGC-CAP-003".equals(b.code()))) {
                throw new FgcBusinessException(FgcErrorCode.COMMON_002, Map.of("field", "payment",
                        "reason", preview.blockers().getFirst().message()));
            }
            if (cap) exceptions.action(id, new ExceptionActionRequest(type, reason, body.evidenceRef()), actor, login);
            corrected = payments.confirmExceptionCorrection(corrected.paymentId(), "EXCP-" + id + "-" + corrected.paymentId());
            if ("RECONCILIATION_MISMATCH".equals(source.type())
                    || (cap && !runIds.isEmpty() && "PAYMENT".equals(corrected.cashflowType()))) {
                postActual(corrected, source, actor, reason);
            }
        }
        if (CAP_TYPES.contains(source.type()) && cases.findByIdForUpdate(id).status() == ExceptionStatus.IN_REVIEW) {
            exceptions.action(id, new ExceptionActionRequest(type, reason, body.evidenceRef()), actor, login);
        }
        CommissionPaymentResponse deferred = null;
        if ("DEFER".equals(request.plannedAction())) {
            var a = body.attributions().getFirst();
            var date = request.deferredPaymentDate();
            boolean settlementSupport = a.attributionMethod() == AttributionMethod.SETTLEMENT_SUPPORT_MONTHLY;
            deferred = payments.create(new CommissionPaymentCreateRequest("GA_MANUAL_PAYMENT", "EXCP:" + id + ":DEFER:" + old.paymentId(),
                    body.contractId(), body.agentId(), body.commissionItemId(), request.deferredAmount(),
                    settlementSupport ? body.settlementMonth() : date.withDayOfMonth(1), body.cashflowType(),
                    date, body.paymentStage(), null, List.of(new CommissionPaymentAttributionRequest(a.contractId(),
                    settlementSupport ? a.attributionDate() : date, request.deferredAmount(),
                    settlementSupport ? InclusionDecisionStatus.REVIEW_REQUIRED : a.inclusionDecisionStatus(),
                    settlementSupport ? ExclusionType.NONE : a.exclusionType(),
                    settlementSupport ? "정착지원금 지급일 이연 모집월 귀속과 한도 산입 재검토" : a.inclusionDecisionReason(),
                    a.allocationBasis(), a.evidenceRef(), a.attributionMethod())),
                    body.evidenceRef(), "예외 " + id + "의 초과분 이연 원 지급 " + old.paymentId()));
            reason += " 초과분 " + request.deferredAmount() + "원 → " + date + " 지급 초안 " + deferred.paymentId();
            audit.record(AuditLogService.AuditEvent.builder().actionCode("EXCEPTION_PAYMENT_DEFERRED").entityType("EXCEPTION_CASE")
                    .entityId(id.toString()).userId(actor).after(Map.of("deferredPaymentId", deferred.paymentId(), "amount", request.deferredAmount(), "date", date))
                    .reason(reason).build());
        }
        // 원장 정정이 이미 새 실행으로 교체한 대사는 중복 실행하지 않는다.
        var active = new HashSet<>(CAP_TYPES.contains(source.type()) && journal == null
                ? runIds : repository.reconciliationRuns(source));
        reconciliation.refresh(runIds.stream().filter(active::contains).toList(), "EXCP-" + id, actor, reason);
        if (cases.findByIdForUpdate(id).status() == ExceptionStatus.IN_REVIEW) {
            exceptions.recordPendingPaymentCorrection(id, new ExceptionActionRequest(type, reason, body.evidenceRef()), actor, login);
        }
        audit.record(AuditLogService.AuditEvent.builder().actionCode("EXCEPTION_PAYMENT_CORRECTION")
                .entityType("EXCEPTION_CASE").entityId(id.toString()).userId(actor)
                .before(old).after(Map.of("paymentId", corrected.paymentId(), "actionType", type.name()))
                .reason(reason).build());
        boolean resolved = cases.findByIdForUpdate(id).status() == ExceptionStatus.RESOLVED;
        if (resolved && "CAP_VIOLATION".equals(source.type())) {
            var warnings = repository.newWarningsForCorrectedViolation(id, corrected.paymentId(), corrected.capCheckIds());
            for (var warning : warnings) {
                capExceptions.resolve(new CapExceptionResolveCommand(warning.exceptionId(), ExceptionActionType.CORRECT,
                        "위반 예외 " + id + "번 정정 후 한도 주의 잔여 "
                                + warning.remainingAmount().stripTrailingZeros().toPlainString() + "원 추가 지급 전 사전검증",
                        "EXCP-" + id, actor));
            }
        }
        var history = queries.findActionsByCaseIds(List.of(id));
        var latest = history.stream().max(Comparator.comparingInt(ExceptionActionRow::actionSeq))
                .map(ExceptionActionResponse::from).orElse(null);
        var remaining = repository.remainingIssues(source);
        return new ExceptionPaymentCorrectionResponse(corrected, resolved,
                resolved ? type.label() + " 내용을 반영하고 재검증을 통과해 예외를 해결했습니다."
                        : remaining.isEmpty() ? "지급 건을 수정했습니다. 대사 결과에서 예외 해결 여부를 확인하세요."
                        : "지급 건을 수정했습니다. 재대사 결과: " + remaining.getFirst().resultLabel()
                            + " 예상 " + String.format("%,.0f", remaining.getFirst().expectedAmount()) + "원 / 실제 "
                            + String.format("%,.0f", remaining.getFirst().actualAmount()) + "원. 남은 원인을 확인하세요.", latest, remaining, deferred);
    }

    @Transactional
    public ExceptionPaymentCorrectionResponse cancelDuplicate(Long id, ExceptionDuplicateCancelRequest request, Long actor, String login) {
        repository.lockValidations(id);
        var source = repository.source(id);
        var runs = repository.reconciliationRuns(source);
        requireTarget(cases.findByIdForUpdate(id), actor);
        var reco = repository.reconciliationSource(source);
        if (reco == null || !"DUPLICATE".equals(reco.resultType()) || runs.isEmpty()) throw invalid("paymentId");
        var paymentIds = repository.paymentIds(id);
        if (!paymentIds.contains(request.paymentId()) || paymentIds.stream().filter(paymentId -> !paymentId.equals(request.paymentId()))
                .map(payments::get).noneMatch(payment -> payment.status() == CommissionPaymentStatus.CONFIRMED)) throw invalid("paymentId");
        if (!paymentWrites.lockPayment(request.paymentId())) throw invalid("paymentId");
        var old = payments.get(request.paymentId());
        if (old.status() != CommissionPaymentStatus.CONFIRMED || !old.updatedAt().toInstant().equals(request.expectedUpdatedAt().toInstant())) throw new FgcBusinessException(FgcErrorCode.TRAN_010);
        String reason = "중복 지급 기록 취소 지급 " + old.sourceBusinessKey();
        Long journal = repository.postedJournal(old.paymentId());
        if (journal != null) journals.reverse(new ReverseJournalCommand(journal, reason, request.evidenceRef(), actor));
        else {
            if (paymentWrites.cancel(old.paymentId()) != 1) throw invalid("paymentId");
            audit.record(AuditLogService.AuditEvent.builder().actionCode("PAYMENT_CANCELLED").entityType("COMMISSION_TRANSACTION")
                    .entityId(old.paymentId().toString()).userId(actor).before(old).after(Map.of("exceptionCaseId", id, "status", "CANCELLED")).reason(reason).build());
        }
        var active = new HashSet<>(repository.reconciliationRuns(source));
        reconciliation.refresh(runs.stream().filter(active::contains).toList(), "EXCP-" + id, actor, reason);
        if (cases.findByIdForUpdate(id).status() == ExceptionStatus.IN_REVIEW) exceptions.recordPendingPaymentCorrection(id,
                new ExceptionActionRequest(ExceptionActionType.CANCEL, reason, request.evidenceRef()), actor, login);
        var latest = queries.findActionsByCaseIds(List.of(id)).stream().max(Comparator.comparingInt(ExceptionActionRow::actionSeq)).map(ExceptionActionResponse::from).orElse(null);
        boolean resolved = cases.findByIdForUpdate(id).status() == ExceptionStatus.RESOLVED;
        return new ExceptionPaymentCorrectionResponse(payments.get(old.paymentId()), resolved,
                resolved ? "중복 지급 기록을 역분개 및 취소하고 재대사 일치를 확인했습니다." : "선택한 기록을 취소하고 재대사했습니다. 남은 대사 차이를 확인하세요.",
                latest, repository.remainingIssues(source), null);
    }

    @Transactional
    public ExceptionSourceRecheckResponse reconcileRecovery(Long id, ExceptionRecoveryRecheckRequest request, Long actor, String login) {
        repository.lockValidations(id);
        var source = repository.source(id);
        var reco = repository.reconciliationSource(source);
        if (reco == null || !"RECOVERY".equals(correctionTarget(null, reco))
                || !repository.paymentIds(id).contains(request.originalPaymentId())
                || request.originalPaymentId().equals(request.deductionPaymentId())) throw invalid("originalPaymentId");
        var runs = repository.reconciliationRuns(source);
        if (runs.isEmpty()) throw invalid("reconciliationRun");
        for (Long paymentId : List.of(request.originalPaymentId(), request.deductionPaymentId()).stream().sorted().toList()) {
            if (!paymentWrites.lockPayment(paymentId)) throw invalid("deductionPaymentId");
        }
        var target = cases.findByIdForUpdate(id);
        if (!source.equals(repository.source(id))) throw invalid("exceptionCaseId");
        if (target != null && actor.equals(target.assignedTo())
                && (target.status() == ExceptionStatus.IN_REVIEW || target.status() == ExceptionStatus.RESOLVED)
                && repository.hasConfirmedRecovery(source, request.originalPaymentId(), request.deductionPaymentId(), request.candidateId())) {
            var previous = recoveryAction(id, request.originalPaymentId());
            if (previous.isPresent()) return recoveryResponse(previous.get());
        }
        requireTarget(target, actor);
        var original = payments.get(request.originalPaymentId());
        var deduction = payments.get(request.deductionPaymentId());
        var detail = clawbacks.detail(request.candidateId());
        var candidate = detail.candidate();
        var calculation = detail.latestCalculation();
        if (calculation == null || !Objects.equals(candidate.contractId(), source.contractId())
                || !"STATUS_EVENT".equals(candidate.triggerType().name())
                || !"CALCULATED".equals(candidate.calculationStatus().name())
                || !Objects.equals(candidate.sourceVersion(), calculation.sourceVersion())) throw invalid("clawbackCalculation");
        var originalAttributions = detail.paymentAttributions().stream()
                .filter(row -> Objects.equals(row.commissionTransactionId(), original.paymentId()))
                .map(row -> row.transactionAttributionId()).collect(java.util.stream.Collectors.toSet());
        BigDecimal amount = calculation.lines().stream().filter(line -> originalAttributions.contains(line.transactionAttributionId()))
                .map(line -> line.clawbackAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        String recoveryKey = original.paymentId() + ":" + request.candidateId();
        if (amount.signum() <= 0 || original.status() != CommissionPaymentStatus.CONFIRMED
                || deduction.status() != CommissionPaymentStatus.CONFIRMED || !"DEDUCTION".equals(deduction.cashflowType())
                || !"CLAWBACK".equals(deduction.commissionItemCode())
                || !("GA_MANUAL_PAYMENT".equals(deduction.sourceType()) || "CLAWBACK".equals(deduction.sourceType()))
                || deduction.paymentStage() != original.paymentStage() || !Objects.equals(deduction.agentId(), original.agentId())
                || !Objects.equals(deduction.contractId(), source.contractId()) || deduction.amount().compareTo(amount) != 0
                || deduction.attributions().isEmpty() || deduction.attributions().stream().anyMatch(row -> !Objects.equals(row.contractId(), source.contractId()))
                || !(Objects.equals(deduction.sourceBusinessKey(), "CLAWBACK-RECOVERY:" + recoveryKey)
                    || Objects.equals(deduction.sourceBusinessKey(), "EXCP-RECOVERY:" + id + ":" + recoveryKey))
                || deduction.evidenceRef() == null || deduction.evidenceRef().isBlank()) throw invalid("deductionPaymentId");
        String calculationRef = "환수 계산 v" + calculation.calculationVersion() + " 원지급 " + original.paymentId();
        String noticeRef = calculationRef.equals(deduction.evidenceRef().replace(" · ", " ")) ? null : deduction.evidenceRef();
        if (!repository.registerRecovery(source, original.paymentId(), deduction.paymentId(), request.candidateId(),
                calculation.calculationVersion(), noticeRef)) throw invalid("recoveryEvidence");

        String evidence = (noticeRef == null ? calculationRef : "환수 통지서 " + noticeRef) + " 차감 " + deduction.sourceBusinessKey();
        String reason = "원지급 " + original.paymentId() + "번의 환수 차감 " + amount.stripTrailingZeros().toPlainString() + "원 확정 확인 " + evidence;
        reconciliation.refresh(runs, "EXCP-RECOVERY-" + id, actor, reason);
        boolean resolved = repository.recoveryComplete(source) && repository.remainingIssues(source).isEmpty();
        var actionRequest = new ExceptionActionRequest(ExceptionActionType.RECONCILE_AGAIN, reason, evidence);
        var recorded = resolved ? exceptions.action(id, actionRequest, actor, login)
                : exceptions.recordPendingPaymentCorrection(id, actionRequest, actor, login);
        return recoveryResponse(recoveryAction(id, original.paymentId()).orElse(recorded));
    }

    private Optional<ExceptionActionResponse> recoveryAction(Long id, Long originalPaymentId) {
        String reasonPrefix = "원지급 " + originalPaymentId + "번의 환수 차감 ";
        return queries.findActionsByCaseIds(List.of(id)).stream()
                .filter(row -> ExceptionActionType.RECONCILE_AGAIN.name().equals(row.actionType())
                        && row.reason() != null && row.reason().startsWith(reasonPrefix))
                .max(Comparator.comparingInt(ExceptionActionRow::actionSeq)).map(ExceptionActionResponse::from);
    }

    private ExceptionSourceRecheckResponse recoveryResponse(ExceptionActionResponse action) {
        boolean resolved = action.toStatus() == ExceptionStatus.RESOLVED;
        return new ExceptionSourceRecheckResponse(null, resolved, resolved ? "환수 차감 확정을 확인해 재대사하고 예외를 해결했습니다."
                : "환수 차감을 반영했습니다. 같은 예외에 남은 지급 건을 확인하세요.", action);
    }

    @Transactional
    public ExceptionSourceRecheckResponse recheckSchedule(Long id, Long actor, String login) {
        repository.lockValidations(id);
        var source = repository.source(id);
        var scope = repository.capScope(id);
        var runs = repository.reconciliationRuns(source);
        requireTarget(cases.findByIdForUpdate(id), actor);
        if ("RECONCILIATION_MISMATCH".equals(source.type())) {
            if (runs.isEmpty()) throw invalid("reconciliationRun");
            reconciliation.refresh(runs, "EXCP-SCHEDULE-" + id, actor);
            boolean resolved = cases.findByIdForUpdate(id).status() == ExceptionStatus.RESOLVED;
            var action = queries.findActionsByCaseIds(List.of(id)).stream().max(Comparator.comparingInt(ExceptionActionRow::actionSeq))
                    .map(ExceptionActionResponse::from).orElse(null);
            return new ExceptionSourceRecheckResponse(null, resolved,
                    resolved ? "스케줄 및 계약 정정 후 재대사가 일치해 예외를 해결했습니다." : "재대사에서 원인이 남아 있습니다. 계약 상태와 예상 스케줄을 확인하세요.", action);
        }
        if (scope == null || !scope.scheduledBasis()) throw invalid("scheduleCorrectionRequired");
        var saved = capChecks.calculateAndSave(com.susukkang.fgc.cap.dto.CapCalculationCommand.manual(
                scope.contractId(), scope.stage(), scope.asOfDate(), null));
        var status = saved.result().resultStatus();
        boolean resolved = status != CapResultStatus.VIOLATION && status != CapResultStatus.REVIEW_REQUIRED
                && !("CAP_WARNING".equals(source.type()) && status == CapResultStatus.WARNING);
        String message = resolved ? "수정된 예상 스케줄이 한도 검증을 통과해 예외를 해결했습니다."
                : status == CapResultStatus.REVIEW_REQUIRED ? "예상 스케줄을 검증할 자료가 부족합니다. 계산근거를 확인하세요."
                : "예상 지급액 " + com.susukkang.fgc.common.util.DisplayFormat.won(saved.result().includedAmount())
                    + "원이 한도 " + com.susukkang.fgc.common.util.DisplayFormat.won(saved.result().limitAmount()) + "원을 초과합니다. 정책 규칙과 스케줄을 정정하세요.";
        ExceptionActionResponse action = resolved ? exceptions.action(id,
                new ExceptionActionRequest(ExceptionActionType.CORRECT, "예상 스케줄 재검증 통과 계산근거 " + saved.capCheckId(), null), actor, login) : null;
        return new ExceptionSourceRecheckResponse(com.susukkang.fgc.cap.dto.CapCheckBasisResponse.from(saved,
                capChecks.findDetail(scope.checkId()).map(d -> d.capCheck().contractNo()).orElse(null)), resolved, message, action);
    }

    private void requirePaymentBasis(Long id) {
        var scope = repository.capScope(id);
        var source = repository.source(id);
        if (!"PAYMENT".equals(correctionTarget(scope, repository.reconciliationSource(source)))) throw invalid("scheduleCorrectionRequired");
    }

    private void requireCorrectionInput(ExceptionPaymentRepository.Source source, ExceptionPaymentCorrectionRequest request) {
        var reco = repository.reconciliationSource(source);
        if (request.plannedAction() != null) {
            if (!CAP_TYPES.contains(source.type()) || request.paymentId() == null) throw invalid("plannedAction");
            var old = payments.get(request.paymentId());
            if (request.payment().amount().compareTo(old.amount()) >= 0) throw invalid("plannedAction");
            if ("DEFER".equals(request.plannedAction()) && (request.deferredAmount() == null || request.deferredAmount().signum() <= 0
                    || request.deferredPaymentDate() == null || request.payment().attributions().size() != 1
                    || request.payment().amount().add(request.deferredAmount()).compareTo(old.amount()) != 0
                    || repository.deferrableFrom(source.contractId()) == null
                    || request.deferredPaymentDate().isBefore(repository.deferrableFrom(source.contractId())))) throw invalid("deferredPayment");
        }
        if (request.paymentId() == null) {
            if (reco == null || !"ACTUAL_MISSING".equals(reco.resultType())) throw invalid("newPaymentNotMissing");
            if (repository.hasRegisteredActual(reco)) throw invalid("alreadyRegistered");
        }
        if (reco != null && "ACTUAL_MISSING".equals(reco.resultType())) {
            var body = request.payment();
            if (body.paymentStage() != reco.stage() || !Objects.equals(body.contractId(), reco.contractId())
                    || !Objects.equals(body.agentId(), reco.stage() == PaymentStage.GA_TO_FC ? reco.expectedAgentId() : null)
                    || !Objects.equals(body.commissionItemId(), reco.commissionItemId())
                    || !Objects.equals(body.settlementMonth(), reco.month()) || body.amount().compareTo(reco.expectedAmount()) != 0
                    || !"PAYMENT".equals(body.cashflowType()) || body.attributions().size() != 1
                    || !Objects.equals(body.attributions().getFirst().contractId(), reco.contractId())
                    || !java.time.YearMonth.from(body.attributions().getFirst().attributionDate()).equals(java.time.YearMonth.from(reco.month()))) {
                throw invalid("missingPaymentScope");
            }
        }
        if (reco != null && "AMOUNT_DIFFERENCE".equals(reco.resultType())) {
            if (!Set.of("RECORD_ONLY", "SETTLEMENT_COMPLETED").contains(Objects.toString(request.correctionMode(), ""))) throw invalid("correctionMode");
            if ("SETTLEMENT_COMPLETED".equals(request.correctionMode())
                    && (request.settlementEvidence() == null || request.settlementEvidence().isBlank())) throw invalid("settlementEvidence");
        }
    }

    private String correctionTarget(ExceptionPaymentRepository.CapScope scope, ExceptionPaymentRepository.ReconciliationSource reco) {
        if (scope != null && scope.scheduledBasis()) return "SCHEDULE";
        if (reco == null) return "PAYMENT";
        if ("INVALID_CONTRACT_PAYMENT".equals(reco.resultType())) return reco.stage() == PaymentStage.GA_TO_FC ? "RECOVERY" : "CONTRACT";
        if (reco.expectedAmount().signum() == 0 && Set.of("COMMON_COST", "SETTLEMENT_SUPPORT", "NEWCOMER_SUPPORT").contains(Objects.toString(reco.itemCode(), ""))) return "REVIEW";
        if ("EXPECTED_MISSING".equals(reco.resultType())) return "SCHEDULE";
        if (reco.expectedAmount().signum() == 0) return "SCHEDULE";
        return "PAYMENT";
    }

    private void requireTarget(ExceptionCaseActionTarget target, Long actor) {
        if (target == null) throw invalid("exceptionCaseId");
        exceptions.requireReviewer(target, actor);
    }
    private void requireSupported(ExceptionPaymentRepository.Source source) {
        if (!CAP_TYPES.contains(source.type()) && !"RECONCILIATION_MISMATCH".equals(source.type())) throw invalid("exceptionType");
    }
    private ExceptionActionType inferAction(CommissionPaymentResponse old, CommissionPaymentCreateRequest next) {
        if (old == null) return ExceptionActionType.CORRECT;
        if (next.amount().compareTo(old.amount()) < 0) return ExceptionActionType.REDUCE;
        if (next.settlementMonth().isAfter(old.settlementMonth()) || next.scheduledPaymentDate().isAfter(old.scheduledPaymentDate())
                || next.attributions().stream().anyMatch(a -> old.attributions().stream()
                    .filter(o -> Objects.equals(o.contractId(), a.contractId())).anyMatch(o -> a.attributionDate().isAfter(o.attributionDate())))) return ExceptionActionType.DEFER;
        return ExceptionActionType.CORRECT;
    }
    private String correctionReason(ExceptionActionType type, CommissionPaymentResponse old, CommissionPaymentCreateRequest next) {
        return type.label() + " 처리 " + (old == null ? "신규 지급 등록" : "지급 " + old.paymentId() + " " + old.amount() + "원 → " + next.amount() + "원")
                + " 정산월 " + next.settlementMonth() + " 지급예정일 " + next.scheduledPaymentDate();
    }
    private CommissionPaymentCreateRequest normalized(Long id, CommissionPaymentResponse old, CommissionPaymentCreateRequest p, String reason) {
        return new CommissionPaymentCreateRequest("GA_MANUAL_PAYMENT",
                old != null && old.status() == CommissionPaymentStatus.DRAFT ? old.sourceBusinessKey() : "EXCP:" + id + ":" + (old == null ? "NEW" : old.paymentId()),
                p.contractId(), p.agentId(), p.commissionItemId(), p.amount(), p.settlementMonth(), p.cashflowType(),
                p.scheduledPaymentDate(), p.paymentStage(), p.allocationPolicyVersion(), p.attributions(), p.evidenceRef(), reason);
    }
    private FgcBusinessException invalid(String field) {
        String reason = switch (field) {
            case "scheduleCorrectionRequired" -> "이 예외는 계약 및 예상 스케줄을 확인하고 재검증하세요.";
            case "newPaymentNotMissing" -> "금액 차이는 별도 지급으로 등록할 수 없습니다. 기존 지급 건의 전체 금액을 정정하세요.";
            case "alreadyRegistered" -> "같은 수령인, 항목 및 회차의 지급 건이 있습니다. 기존 초안을 확정하거나 이미 확정한 경우 재대사하세요.";
            case "missingPaymentScope" -> "누락 지급은 예상 스케줄의 계약, 수령인, 항목, 회차, 정산월 및 금액에 맞춰 등록하세요.";
            case "correctionMode" -> "기록 오류 정정인지 실제 지급 차이 정산 후 정정인지 선택하세요.";
            case "settlementEvidence" -> "추가 송금 또는 반환 처리 증빙을 입력하세요.";
            case "plannedAction" -> "한도 초과 지급의 기존 금액을 줄이고 감액 또는 초과분 이연을 선택하세요.";
            case "deferredPayment" -> "현재 지급액과 이연액의 합계는 원 지급액이어야 하며, 이연 지급일은 계약 2차년도 이후여야 합니다.";
            default -> "예외와 연결된 지급 원천 및 최신 상태를 확인하세요.";
        };
        return new FgcBusinessException(FgcErrorCode.COMMON_002, Map.of("field", field, "reason", reason));
    }

    private void postActual(CommissionPaymentResponse payment, ExceptionPaymentRepository.Source source, Long actor, String reason) {
        if (!"PAYMENT".equals(payment.cashflowType())) throw invalid("cashflowType");
        boolean payout = payment.paymentStage() == PaymentStage.GA_TO_FC;
        var lines = new ArrayList<JournalLineDraft>();
        for (var a : payment.attributions()) {
            if (a.amount().signum() == 0) continue;
            lines.add(JournalLineDraft.builder().lineNo(lines.size() + 1)
                    .accountCode(payout ? JournalAccountCode.CONFIRMED_PAYOUT_EXPENSE : JournalAccountCode.ACTUAL_RECEIVABLE)
                    .debitAmount(a.amount()).creditAmount(java.math.BigDecimal.ZERO).contractId(a.contractId())
                    .agentId(payout ? payment.agentId() : null).paymentStage(payment.paymentStage())
                    .commissionItemId(payment.commissionItemId()).memo(reason).build());
            lines.add(JournalLineDraft.builder().lineNo(lines.size() + 1)
                    .accountCode(payout ? JournalAccountCode.CONFIRMED_PAYOUT_PAYABLE : JournalAccountCode.ACTUAL_INCOME)
                    .debitAmount(java.math.BigDecimal.ZERO).creditAmount(a.amount()).contractId(a.contractId())
                    .agentId(payout ? payment.agentId() : null).paymentStage(payment.paymentStage())
                    .commissionItemId(payment.commissionItemId()).memo(reason).build());
        }
        var draft = JournalHeaderDraft.builder().journalType(payout ? JournalType.CONFIRMED_FC_PAYOUT : JournalType.ACTUAL_INSURER_STATEMENT)
                .journalDate(payment.scheduledPaymentDate()).sourceEntityType("COMMISSION_TRANSACTION")
                .sourceEntityId(payment.paymentId().toString()).revisionNo(1).validationRunId(source.validationRunId())
                .contractId(payment.contractId()).policyVersionId(payment.allocationPolicyVersion()).description(reason).lines(lines).build();
        var header = journalPersistence.saveDraftInCurrentTransaction(draft, actor, com.susukkang.fgc.common.web.RequestIdContext.current());
        if (!"POSTED".equals(header.getStatus()) && journalHeaders.markPosted(header.getJournalHeaderId(), actor) != 1) throw invalid("journal");
    }
}
