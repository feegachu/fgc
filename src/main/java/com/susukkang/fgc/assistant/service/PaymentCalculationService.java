package com.susukkang.fgc.assistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.assistant.dto.PaymentCalculationRequest;
import com.susukkang.fgc.assistant.dto.PaymentCalculationSnapshot;
import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.util.MoneyUtil;
import com.susukkang.fgc.transaction.dto.CommissionPaymentResponse;
import com.susukkang.fgc.transaction.dto.TransactionPrecheckResponse;
import com.susukkang.fgc.transaction.service.CommissionPaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PaymentCalculationService {
    private final CommissionPaymentService payments;
    private final ObjectMapper mapper;
    private static final Set<String> CAP_MONEY = Set.of("basePremiumAmount", "refund12mAmount", "complianceDeductionAmount", "limitAmount", "existingIncludedAmount", "candidateAmount", "includedAmount", "remainingAmount");
    private static final Set<String> CAP_IDS = Set.of("contractId", "capRuleSetId", "capCheckId");

    @PreAuthorize(Roles.ANY_ROLE)
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PaymentCalculationSnapshot calculate(PaymentCalculationRequest request) {
        if (request.kind() == PaymentCalculationRequest.Kind.PAYMENT_CANDIDATE) requireProcessing();
        if (request.mode() != PaymentCalculationRequest.Mode.EXISTING ||
                !Set.of(PaymentCalculationRequest.Kind.ATTRIBUTION_BALANCE, PaymentCalculationRequest.Kind.PAYMENT_CANDIDATE).contains(request.kind()))
            return result(request, "UNSUPPORTED", null, Map.of(), List.of(), null, List.of(), List.of(), "아직 등록되지 않은 계산 범위입니다.");
        if (request.candidate() != null && !request.candidate().isNull()) throw invalid("candidate", "EXISTING 계산에는 후보를 전달할 수 없습니다.");
        if (request.scope() == null || request.scope().paymentId() == null)
            return result(request, "INSUFFICIENT_DATA", null, Map.of(), List.of(), null, List.of(), List.of("paymentId"), "저장된 지급 건을 선택하세요.");
        long id;
        try { id = Long.parseLong(request.scope().paymentId()); if (id <= 0) throw new NumberFormatException(); }
        catch (NumberFormatException error) { throw invalid("scope.paymentId", "지급 ID 범위를 확인하세요."); }
        CommissionPaymentResponse payment = payments.get(id);
        if (!Objects.equals(payment.paymentId(), id)) throw new IllegalStateException("Payment scope mismatch");
        if (request.kind() == PaymentCalculationRequest.Kind.PAYMENT_CANDIDATE) {
            if (payment.status() != CommissionPaymentStatus.DRAFT)
                return result(request, "UNSUPPORTED", payment, Map.of(), List.of(), null, List.of(), List.of(), "지급 후보 검토는 DRAFT만 지원합니다.");
            TransactionPrecheckResponse checked = payments.precheck(id);
            if (!Objects.equals(checked.paymentId(), id)) throw new IllegalStateException("Precheck scope mismatch");
            List<Map<String, Object>> previews = checked.capPreview().stream().map(this::preview).toList();
            List<PaymentCalculationSnapshot.Reason> blockers = checked.blockers().stream().map(row -> new PaymentCalculationSnapshot.Reason(row.code(), row.message(), string(row.contractId()), string(row.transactionAttributionId()))).toList();
            return result(request, checked.confirmable() ? "CALCULATED" : "REVIEW_REQUIRED", payment, Map.of(), previews, checked.confirmable(), blockers, List.of(), "기존 계산기의 조회 결과입니다. 실제 사전검증 완료 증표나 확정 승인이 아닙니다.");
        }
        if (payment.amount() == null || payment.attributions() == null || payment.attributions().isEmpty() || payment.attributions().stream().anyMatch(row -> row.amount() == null))
            return result(request, "INSUFFICIENT_DATA", payment, Map.of(), List.of(), null, List.of(), List.of("amount/attributions"), "지급액 또는 귀속 자료가 없습니다. 0원으로 추정하지 않습니다.");
        BigDecimal amount = MoneyUtil.roundWon(payment.amount());
        BigDecimal total = MoneyUtil.sumRounded(payment.attributions().stream().map(row -> row.amount()).toList());
        BigDecimal difference = amount.subtract(total);
        return result(request, difference.signum() == 0 ? "CALCULATED" : "REVIEW_REQUIRED", payment,
                Map.of("amount", amount.toPlainString(), "attributedTotal", total.toPlainString(), "differenceAmount", difference.toPlainString()), List.of(), null, List.of(), List.of(),
                "저장된 지급액과 귀속 합계의 차액입니다. 추가 지급 추천이나 자동 배부가 아닙니다.");
    }
    private Map<String, Object> preview(TransactionPrecheckResponse.CapPreviewItem item) {
        Map<String, Object> result = mapper.convertValue(item, new TypeReference<LinkedHashMap<String, Object>>() {});
        result.put("asOfDate", string(item.asOfDate()));
        // Convert from the typed long fields, never through an IEEE-754 number on the client.
        CAP_MONEY.forEach(key -> { if (result.get(key) != null) result.put(key, result.get(key).toString()); });
        CAP_IDS.forEach(key -> { if (result.get(key) != null) result.put(key, result.get(key).toString()); });
        return result;
    }
    private PaymentCalculationSnapshot result(PaymentCalculationRequest request, String status, CommissionPaymentResponse payment,
            Map<String, String> amounts, List<Map<String, Object>> previews, Boolean confirmable,
            List<PaymentCalculationSnapshot.Reason> blockers, List<String> missing, String message) {
        Map<String, String> scope = new LinkedHashMap<>();
        List<PaymentCalculationSnapshot.Source> sources = List.of();
        if (payment != null) {
            scope.put("paymentId", string(payment.paymentId())); scope.put("settlementMonth", payment.settlementMonth() == null ? null : payment.settlementMonth().toString().substring(0, 7));
            scope.put("paymentStage", string(payment.paymentStage()));
            sources = List.of(new PaymentCalculationSnapshot.Source("payment:" + payment.paymentId(), "PAYMENT", "저장된 지급 및 귀속 자료", string(payment.updatedAt()), string(payment.paymentId())));
        }
        return new PaymentCalculationSnapshot(UUID.randomUUID().toString(), request.kind().name(), request.mode().name(), status, request.clientRevision(), false, true, "ACTUAL", "KRW", payment == null ? null : string(payment.status()), scope, amounts, previews, confirmable, blockers, missing,
                basis(payment, previews, sources),
                new PaymentCalculationSnapshot.Recommendation(false, null, null, message, List.of("SERVER_VALIDITY_PROOF_REQUIRED")), OffsetDateTime.now(), null);
    }
    private PaymentCalculationSnapshot.Basis basis(CommissionPaymentResponse payment,
            List<Map<String, Object>> previews, List<PaymentCalculationSnapshot.Source> sources) {
        var lines = payment == null || payment.attributions() == null
                ? List.<PaymentCalculationSnapshot.AttributionLine>of()
                : payment.attributions().stream().map(row -> new PaymentCalculationSnapshot.AttributionLine(
                        string(row.attributionSequence()), string(row.contractId()), string(row.attributionDate()),
                        row.amount() == null ? null : row.amount().toPlainString(),
                        row.amount() == null ? null : MoneyUtil.roundWon(row.amount()).toPlainString())).toList();
        var dates = previews.isEmpty()
                ? lines.stream().map(PaymentCalculationSnapshot.AttributionLine::attributionDate).filter(Objects::nonNull).distinct().toList()
                : previews.stream().map(row -> string(row.get("asOfDate"))).filter(Objects::nonNull).distinct().toList();
        boolean completeDates = previews.isEmpty() ? !lines.isEmpty() && lines.stream().allMatch(row -> row.attributionDate() != null) : previews.stream().allMatch(row -> row.get("asOfDate") != null);
        var policies = payment == null || payment.allocationPolicyVersion() == null
                ? List.<String>of() : List.of(string(payment.allocationPolicyVersion()));
        var rules = previews.stream().map(row -> string(row.get("capRuleSetId"))).filter(Objects::nonNull).distinct().toList();
        return new PaymentCalculationSnapshot.Basis(sources,
                new PaymentCalculationSnapshot.Rounding(0, "HALF_UP", "ROUND_EACH_LINE_THEN_SUM"),
                completeDates && dates.size() == 1 ? dates.getFirst() : null, policies, rules, lines);
    }
    private void requireProcessing() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities().stream().noneMatch(role -> Set.of("ROLE_SETTLEMENT", "ROLE_SYSTEM_ADMIN").contains(role.getAuthority())))
            throw new AccessDeniedException("지급 후보 조회 권한이 없습니다.");
    }
    private static String string(Object value) { return value == null ? null : value.toString(); }
    private static FgcBusinessException invalid(String field, String detail) { return new FgcBusinessException(FgcErrorCode.COMMON_002, field, Map.of("field", field), detail); }
}
