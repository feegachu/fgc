package com.susukkang.fgc.assistant.service;

import static com.susukkang.fgc.assistant.service.CalculationAmounts.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.susukkang.fgc.assistant.dto.*;
import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.service.CapCalculator;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.security.AgentDataScopeService;
import com.susukkang.fgc.common.util.MoneyUtil;
import com.susukkang.fgc.reconciliation.service.ReconciliationResultQueryService;
import com.susukkang.fgc.schedule.dto.ScheduleLineResponse;
import com.susukkang.fgc.schedule.repository.ScheduleQueryRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AssistantCalculationSources {
  private static final String AGGREGATION = "aggregation";
  private static final String UNSUPPORTED = "UNSUPPORTED";
  private static final String CONTRACT_ID = "contractId";
  private static final String PAYMENT_STAGE = "paymentStage";
  private static final String ACTUAL = "ACTUAL";
  private static final String RECONCILIATION_RESULT_ID = "reconciliationResultId";
  private static final String EXPECTED_AMOUNT = "expectedAmount";
  private static final String CALCULATED = "CALCULATED";
  private static final String SETTLEMENT_MONTH = "settlementMonth";
  private static final String POLICY_VERSION_ID = "policyVersionId";
  private static final String REVIEW_REQUIRED = "REVIEW_REQUIRED";
  private final ScheduleQueryRepository schedules;
  private final CapCalculator caps;
  private final ReconciliationResultQueryService reconciliations;
  private final AgentDataScopeService scopeAccess;
  private final com.susukkang.fgc.cap.repository.CapContractQueryRepository contracts;

  public PaymentCalculationSnapshot calculate(PaymentCalculationRequest request) {
    if (request.mode() == PaymentCalculationRequest.Mode.HYPOTHETICAL
        && request.kind() == PaymentCalculationRequest.Kind.ATTRIBUTION_BALANCE)
      return balance(request);
    if (request.mode() != PaymentCalculationRequest.Mode.EXISTING)
      return result(
          request,
          UNSUPPORTED,
          "HYPOTHETICAL",
          Map.of(),
          Map.of(),
          new ResultDetails(List.of(), List.of(), null, null, false));
    if (request.candidate() != null && !request.candidate().isNull()) throw invalid("candidate");
    var scope = request.scope();
    if (request.kind() == PaymentCalculationRequest.Kind.RECONCILIATION_DIFFERENCE)
      return reconciliation(request, scope);
    List<String> missingFields = sourceMissingFields(request.kind(), scope);
    if (!missingFields.isEmpty()) return missing(request, missingFields.toArray(String[]::new));
    long contract = id(scope.contractId(), CONTRACT_ID);
    scopeAccess.requireContract(contract);
    if (request.kind() == PaymentCalculationRequest.Kind.CAP_STATUS)
      return cap(request, scope, contract);
    if (request.kind() != PaymentCalculationRequest.Kind.EXPECTED_COMMISSION)
      return result(
          request,
          UNSUPPORTED,
          ACTUAL,
          Map.of(),
          Map.of(),
          new ResultDetails(List.of(), List.of(), null, null, false));
    return expected(request, scope, contract);
  }

  private PaymentCalculationSnapshot reconciliation(
      PaymentCalculationRequest request, PaymentCalculationRequest.Scope scope) {
    if (scope == null || scope.reconciliationResultId() == null)
      return missing(request, RECONCILIATION_RESULT_ID);
    var detail = reconciliations.get(id(scope.reconciliationResultId(), RECONCILIATION_RESULT_ID));
    scopeAccess.requireContract(detail.contractId());
    var amounts = new LinkedHashMap<String, String>();
    amounts.put(EXPECTED_AMOUNT, won(detail.expectedTotalAmount()));
    amounts.put("actualAmount", won(detail.actualTotalAmount()));
    amounts.put("differenceAmount", won(detail.differenceAmount()));
    String status = CALCULATED;
    if (REVIEW_REQUIRED.equals(detail.resultType())) status = REVIEW_REQUIRED;
    else if (amounts.values().stream().anyMatch(Objects::isNull)) status = "INSUFFICIENT_DATA";
    var resolved = new LinkedHashMap<String, String>();
    resolved.put(RECONCILIATION_RESULT_ID, scope.reconciliationResultId());
    resolved.put("resultType", detail.resultType());
    return result(
        request,
        status,
        ACTUAL,
        resolved,
        amounts,
        new ResultDetails(
            List.of(),
            List.of(),
            new PaymentCalculationSnapshot.Source(
                "reconciliation:" + scope.reconciliationResultId(),
                "RECONCILIATION",
                "저장된 대사 결과",
                str(detail.createdAt()),
                scope.reconciliationResultId()),
            null,
            false));
  }

  private PaymentCalculationSnapshot cap(
      PaymentCalculationRequest request, PaymentCalculationRequest.Scope scope, long contract) {
    if (scope.asOfDate() == null) return missing(request, "asOfDate");
    if (contracts.findCapViewByContractId(contract) == null) return missing(request, CONTRACT_ID);
    com.susukkang.fgc.cap.dto.CapCalculationResult cap;
    try {
      cap =
          caps.calculate(
              CapCalculationCommand.realtime(
                  contract, scope.paymentStage(), scope.asOfDate(), null));
    } catch (com.susukkang.fgc.common.exception.FgcBusinessException error) {
      if (error.getErrorCode() == com.susukkang.fgc.common.exception.FgcErrorCode.CAP_004)
        return missing(request, "applicableCapRuleSet");
      throw error;
    }
    var amounts = new LinkedHashMap<String, String>();
    amounts.put("limitAmount", won(cap.limitAmount()));
    amounts.put("includedAmount", won(cap.includedAmount()));
    amounts.put("remainingAmount", won(cap.remainingAmount()));
    var snapshot =
        result(
            request,
            capStatus(cap.resultStatus()),
            ACTUAL,
            Map.of(CONTRACT_ID, scope.contractId(), PAYMENT_STAGE, scope.paymentStage().name()),
            amounts,
            new ResultDetails(
                List.of(),
                List.of(),
                new PaymentCalculationSnapshot.Source(
                    "cap:" + contract,
                    "POLICY",
                    "기존 한도 계산 조회",
                    str(cap.capRuleSetId()),
                    scope.contractId()),
                scope.asOfDate().toString(),
                false));
    var preview = new LinkedHashMap<String, Object>();
    preview.put(CONTRACT_ID, scope.contractId());
    preview.put(PAYMENT_STAGE, scope.paymentStage().name());
    preview.put("asOfDate", scope.asOfDate().toString());
    preview.put("capRuleSetId", str(cap.capRuleSetId()));
    preview.put("resultStatus", cap.resultStatus().name());
    preview.put("resultStatusLabel", cap.resultStatus().label());
    preview.put("usagePct", str(cap.usagePct()));
    preview.putAll(amounts);
    var basis =
        new PaymentCalculationSnapshot.Basis(
            snapshot.basis().sourceRefs(),
            snapshot.basis().rounding(),
            snapshot.basis().asOfDate(),
            List.of(),
            cap.capRuleSetId() == null ? List.of() : List.of(cap.capRuleSetId().toString()),
            List.of());
    return new PaymentCalculationSnapshot(
        snapshot.calculationId(),
        snapshot.kind(),
        snapshot.mode(),
        snapshot.status(),
        snapshot.clientRevision(),
        false,
        true,
        snapshot.evidenceKind(),
        "KRW",
        null,
        snapshot.resolvedScope(),
        snapshot.amounts(),
        List.of(preview),
        null,
        List.of(),
        snapshot.missingInputs(),
        basis,
        snapshot.recommendation(),
        snapshot.calculatedAt(),
        null,
        List.of());
  }

  private PaymentCalculationSnapshot expected(
      PaymentCalculationRequest request, PaymentCalculationRequest.Scope scope, long contract) {
    YearMonth month;
    try {
      month = YearMonth.parse(scope.settlementMonth());
    } catch (java.time.DateTimeException e) {
      throw invalid(SETTLEMENT_MONTH);
    }
    var detail = schedules.selectByContractIdAndPaymentStage(contract, scope.paymentStage());
    if (detail == null || detail.getLines() == null) return missing(request, "activeSchedule");
    var selected = selectExpectedLines(detail.getLines(), scope, month);
    if (selected.isEmpty()
        || (scope.aggregation() == PaymentCalculationRequest.Scope.Aggregation.SINGLE_LINE
            && selected.size() != 1)) return missing(request, "scheduleLineSelection");
    if (selected.stream().anyMatch(line -> line.getExpectedAmount() == null))
      return missing(request, EXPECTED_AMOUNT);
    return expectedResult(request, scope, contract, detail, selected);
  }

  private List<String> sourceMissingFields(
      PaymentCalculationRequest.Kind kind, PaymentCalculationRequest.Scope scope) {
    List<String> missingFields = new ArrayList<>();
    if (scope == null || scope.contractId() == null) missingFields.add(CONTRACT_ID);
    if (scope == null || scope.paymentStage() == null) missingFields.add(PAYMENT_STAGE);
    if (kind == PaymentCalculationRequest.Kind.EXPECTED_COMMISSION) {
      if (scope == null) {
        missingFields.add(SETTLEMENT_MONTH);
        missingFields.add(AGGREGATION);
      } else {
        missingFields.addAll(expectedMissingFields(scope));
      }
    }
    return missingFields;
  }

  private List<String> expectedMissingFields(PaymentCalculationRequest.Scope scope) {
    List<String> missingFields = new ArrayList<>();
    if (scope.settlementMonth() == null) missingFields.add(SETTLEMENT_MONTH);
    if (scope.aggregation() == null) missingFields.add(AGGREGATION);
    if (scope.aggregation() == PaymentCalculationRequest.Scope.Aggregation.SINGLE_LINE) {
      if (scope.commissionItemId() == null) missingFields.add("commissionItemId");
      if (scope.installmentNo() == null) missingFields.add("installmentNo");
      if (scope.paymentStage() == com.susukkang.fgc.common.code.PaymentStage.GA_TO_FC
          && scope.beneficiaryAgentId() == null) missingFields.add("beneficiaryAgentId");
    }
    return missingFields;
  }

  private List<ScheduleLineResponse> selectExpectedLines(
      List<ScheduleLineResponse> lines, PaymentCalculationRequest.Scope scope, YearMonth month) {
    return lines.stream()
        .filter(
            line -> line.getDueDate() != null && YearMonth.from(line.getDueDate()).equals(month))
        .filter(line -> !"CANCELLED".equals(line.getLineStatus()))
        .filter(
            line ->
                scope.commissionItemId() == null
                    || Objects.equals(str(line.getCommissionItemId()), scope.commissionItemId()))
        .filter(
            line ->
                scope.beneficiaryAgentId() == null
                    || Objects.equals(
                        str(line.getBeneficiaryAgentId()), scope.beneficiaryAgentId()))
        .filter(
            line ->
                scope.installmentNo() == null
                    || Objects.equals(line.getInstallmentNo(), scope.installmentNo()))
        .toList();
  }

  private PaymentCalculationSnapshot expectedResult(
      PaymentCalculationRequest request,
      PaymentCalculationRequest.Scope scope,
      long contract,
      com.susukkang.fgc.schedule.dto.ScheduleDetailResponse detail,
      List<ScheduleLineResponse> selected) {
    var lines =
        selected.stream()
            .map(
                line ->
                    new PaymentCalculationSnapshot.CalculationLine(
                        str(detail.getScheduleHeaderId()) + ":" + line.getLineNo(),
                        str(line.getContractId()),
                        str(line.getCommissionItemId()),
                        str(line.getBeneficiaryAgentId()),
                        line.getInstallmentNo(),
                        str(line.getAttributionDate()),
                        str(line.getDueDate()),
                        line.getBasisCode(),
                        str(line.getBasisAmount()),
                        line.getCalculationType(),
                        str(line.getRatePct()),
                        won(line.getExpectedAmount()),
                        str(line.getRuleRef()),
                        line.getLineStatus(),
                        line.getCashflowType()))
            .toList();
    boolean nonPayment =
        selected.stream().anyMatch(line -> !"PAYMENT".equals(line.getCashflowType()));
    boolean held =
        nonPayment || selected.stream().anyMatch(line -> "HOLD".equals(line.getLineStatus()));
    var total =
        MoneyUtil.sumRounded(
            selected.stream().map(ScheduleLineResponse::getExpectedAmount).toList());
    var resolved = new LinkedHashMap<String, String>();
    resolved.put(CONTRACT_ID, scope.contractId());
    resolved.put(SETTLEMENT_MONTH, scope.settlementMonth());
    resolved.put(PAYMENT_STAGE, scope.paymentStage().name());
    resolved.put(AGGREGATION, scope.aggregation().name());
    Long policy = schedules.selectActiveOperationalPolicyVersionId(contract, scope.paymentStage());
    if (policy != null) resolved.put(POLICY_VERSION_ID, policy.toString());
    var dates = selected.stream().map(ScheduleLineResponse::getDueDate).distinct().toList();
    return result(
        request,
        held ? REVIEW_REQUIRED : CALCULATED,
        "EXPECTED",
        resolved,
        nonPayment ? Map.of() : Map.of(EXPECTED_AMOUNT, total.toPlainString()),
        new ResultDetails(
            lines,
            List.of(),
            new PaymentCalculationSnapshot.Source(
                "schedule:" + detail.getScheduleHeaderId(),
                "SCHEDULE",
                "사용 중인 예상 스케줄",
                str(detail.getHeader().getGeneratedAt()),
                str(detail.getScheduleHeaderId())),
            dates.size() == 1 ? dates.getFirst().toString() : null,
            !held && policy != null && selected.size() == 1));
  }

  private PaymentCalculationSnapshot balance(PaymentCalculationRequest request) {
    if (request.scope() != null) throw invalid("scope");
    JsonNode candidate = request.candidate();
    if (candidate == null
        || !candidate.isObject()
        || candidate.size() != 1
        || !candidate.has("balance")) throw invalid("candidate.balance");
    JsonNode balance = candidate.get("balance");
    if (!balance.isObject() || balance.size() != 2) throw invalid("candidate.balance");
    BigDecimal amount = input(balance.get("amount"), "candidate.balance.amount");
    JsonNode rows = balance.get("attributionAmounts");
    if (rows == null || !rows.isArray() || rows.isEmpty() || rows.size() > 1000)
      throw invalid("candidate.balance.attributionAmounts");
    var values = new ArrayList<BigDecimal>();
    for (JsonNode row : rows) values.add(input(row, "candidate.balance.attributionAmounts"));
    BigDecimal total = MoneyUtil.sumRounded(values);
    BigDecimal difference = MoneyUtil.roundWon(amount).subtract(total);
    return result(
        request,
        difference.signum() == 0 ? CALCULATED : REVIEW_REQUIRED,
        "HYPOTHETICAL",
        Map.of(),
        Map.of(
            "amount",
            won(amount),
            "attributedTotal",
            total.toPlainString(),
            "differenceAmount",
            difference.toPlainString()),
        new ResultDetails(List.of(), List.of(), null, null, false));
  }

  private PaymentCalculationSnapshot missing(PaymentCalculationRequest request, String... fields) {
    return result(
        request,
        "INSUFFICIENT_DATA",
        ACTUAL,
        Map.of(),
        Map.of(),
        new ResultDetails(List.of(), List.of(fields), null, null, false));
  }

  private record ResultDetails(
      List<PaymentCalculationSnapshot.CalculationLine> lines,
      List<String> missing,
      PaymentCalculationSnapshot.Source source,
      String asOfDate,
      boolean recommend) {}

  private PaymentCalculationSnapshot result(
      PaymentCalculationRequest r,
      String status,
      String evidence,
      Map<String, String> scope,
      Map<String, String> amounts,
      ResultDetails details) {
    var lines = details.lines();
    var missing = details.missing();
    var source = details.source();
    var asOfDate = details.asOfDate();
    boolean recommend = details.recommend();
    var sources = source == null ? List.<PaymentCalculationSnapshot.Source>of() : List.of(source);
    var basis =
        new PaymentCalculationSnapshot.Basis(
            sources,
            new PaymentCalculationSnapshot.Rounding(0, "HALF_UP", "ROUND_EACH_LINE_THEN_SUM"),
            asOfDate,
            scope.get(POLICY_VERSION_ID) == null
                ? List.of()
                : List.of(scope.get(POLICY_VERSION_ID)),
            lines.stream()
                .map(PaymentCalculationSnapshot.CalculationLine::ruleId)
                .filter(Objects::nonNull)
                .distinct()
                .toList(),
            List.of());
    return new PaymentCalculationSnapshot(
        UUID.randomUUID().toString(),
        r.kind().name(),
        r.mode().name(),
        status,
        r.clientRevision(),
        false,
        true,
        evidence,
        "KRW",
        null,
        scope,
        amounts,
        List.of(),
        null,
        List.of(),
        missing,
        basis,
        new PaymentCalculationSnapshot.Recommendation(
            false,
            recommend ? "payment.amount" : null,
            recommend ? amounts.get(EXPECTED_AMOUNT) : null,
            recommend ? "예상액입니다. 실제 명세와 대조하고 사용 직전 재검증하세요." : "읽기 전용 계산입니다. 실제 지급 처리 완료를 뜻하지 않습니다.",
            List.of("SERVER_REVALIDATION_REQUIRED")),
        OffsetDateTime.now(ZoneOffset.UTC),
        null,
        lines);
  }

  private String capStatus(CapResultStatus status) {
    if (status == CapResultStatus.NOT_APPLICABLE) return UNSUPPORTED;
    if (status == CapResultStatus.REVIEW_REQUIRED) return REVIEW_REQUIRED;
    return CALCULATED;
  }

  private static String won(BigDecimal value) {
    return value == null ? null : MoneyUtil.roundWon(value).toPlainString();
  }

  private static String str(Object value) {
    return value == null ? null : value.toString();
  }
}
