package com.susukkang.fgc.assistant.service;

import static com.susukkang.fgc.assistant.service.CalculationAmounts.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.susukkang.fgc.assistant.dto.PaymentCalculationRequest;
import com.susukkang.fgc.transaction.dto.*;
import com.susukkang.fgc.transaction.service.CommissionPaymentService;
import jakarta.validation.Validator;
import java.time.YearMonth;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AssistantPaymentCandidate {
  private final CommissionPaymentService payments;
  private final ObjectMapper mapper;
  private final Validator validator;
  private static final String PAYMENT = "payment";
  private static final String AMOUNT = "amount";

  public TransactionPrecheckResponse calculate(PaymentCalculationRequest request) {
    JsonNode c = request.candidate();
    if (c == null || !c.isObject() || c.size() != 2 || !c.has("operation") || !c.has(PAYMENT))
      throw invalid("candidate");
    String operation = c.path("operation").asText();
    if (!Set.of("NEW_PAYMENT", "REPLACE_DRAFT").contains(operation))
      throw invalid("candidate.operation");
    Long replaced =
        request.scope() == null || request.scope().paymentId() == null
            ? null
            : id(request.scope().paymentId(), "paymentId");
    if (operation.equals("REPLACE_DRAFT") != (replaced != null)) throw invalid("scope.paymentId");
    if (!c.get(PAYMENT).isObject()) throw invalid("candidate.payment");
    ObjectNode payment = ((ObjectNode) c.get(PAYMENT)).deepCopy();
    normalizePayment(payment);
    normalizeAttributions(payment);
    CommissionPaymentCreateRequest value;
    try {
      value = mapper.treeToValue(payment, CommissionPaymentCreateRequest.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) {
      throw invalid("candidate.payment");
    }
    var errors = validator.validate(value);
    if (!errors.isEmpty())
      throw invalid("candidate.payment." + errors.iterator().next().getPropertyPath());
    return payments.precheckCandidate(value, replaced);
  }

  private static void normalizePayment(ObjectNode payment) {
    Set<String> allowed =
        Arrays.stream(CommissionPaymentCreateRequest.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .collect(java.util.stream.Collectors.toSet());
    payment
        .fieldNames()
        .forEachRemaining(
            name -> {
              if (!allowed.contains(name)) throw invalid("candidate.payment." + name);
            });
    payment.put(AMOUNT, input(payment.get(AMOUNT), "candidate.payment.amount"));
    for (String name :
        List.of("contractId", "agentId", "commissionItemId", "allocationPolicyVersion"))
      normalizeId(payment, name);
    try {
      payment.put(
          "settlementMonth",
          YearMonth.parse(payment.path("settlementMonth").asText()).atDay(1).toString());
    } catch (java.time.DateTimeException e) {
      throw invalid("candidate.payment.settlementMonth");
    }
  }

  private static void normalizeAttributions(ObjectNode payment) {
    JsonNode rows = payment.get("attributions");
    if (rows == null || !rows.isArray() || rows.isEmpty() || rows.size() > 1000)
      throw invalid("candidate.payment.attributions");
    Set<String> attrAllowed =
        Arrays.stream(CommissionPaymentAttributionRequest.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .collect(java.util.stream.Collectors.toSet());
    for (JsonNode node : rows) {
      if (!node.isObject()) throw invalid("candidate.payment.attributions");
      ObjectNode row = (ObjectNode) node;
      row.fieldNames()
          .forEachRemaining(
              name -> {
                if (!attrAllowed.contains(name))
                  throw invalid("candidate.payment.attributions." + name);
              });
      row.put(AMOUNT, input(row.get(AMOUNT), "candidate.payment.attributions.amount"));
      normalizeId(row, "contractId");
    }
  }

  private static void normalizeId(ObjectNode row, String field) {
    JsonNode value = row.get(field);
    if (value != null && !value.isNull()) {
      if (!value.isTextual()) throw invalid(field);
      row.put(field, id(value.textValue(), field));
    }
  }
}
