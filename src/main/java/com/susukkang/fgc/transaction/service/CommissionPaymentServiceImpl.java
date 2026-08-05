package com.susukkang.fgc.transaction.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.cap.dto.CapValidationRequest;
import com.susukkang.fgc.cap.dto.CapValidationResult;
import com.susukkang.fgc.cap.service.CapValidator;
import com.susukkang.fgc.common.code.AttributionMethod;
import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.code.InclusionDecisionStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.transaction.domain.CapCheckCommand;
import com.susukkang.fgc.transaction.domain.CapRuleSnapshot;
import com.susukkang.fgc.transaction.domain.CommissionItemReference;
import com.susukkang.fgc.transaction.domain.CommissionPaymentCommand;
import com.susukkang.fgc.transaction.domain.CommissionPaymentRow;
import com.susukkang.fgc.transaction.domain.ConfirmationData;
import com.susukkang.fgc.transaction.domain.ContractReference;
import com.susukkang.fgc.transaction.domain.ExceptionCaseCommand;
import com.susukkang.fgc.transaction.dto.CommissionPaymentCreateRequest;
import com.susukkang.fgc.transaction.dto.CommissionPaymentResponse;
import com.susukkang.fgc.transaction.dto.CommissionPaymentUpdateRequest;
import com.susukkang.fgc.transaction.mapper.CommissionPaymentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CommissionPaymentServiceImpl implements CommissionPaymentService {

    private final CommissionPaymentMapper mapper;
    private final ObjectMapper objectMapper;
    private final CapValidator capValidator;

    @Override
    @Transactional
    public CommissionPaymentResponse create(CommissionPaymentCreateRequest request) {
        CommissionPaymentCommand command = commandFrom(request);
        mapper.insertTransaction(command);
        mapper.insertAttribution(command);
        return requirePayment(command.getPaymentId()).toResponse();
    }

    @Override
    @Transactional
    public CommissionPaymentResponse update(
            Long paymentId,
            CommissionPaymentUpdateRequest request
    ) {
        ConfirmationData current = requireConfirmationData(paymentId);
        requireDraft(current);

        CommissionPaymentCommand command = commandFrom(paymentId, request);
        mapper.updateTransaction(command);
        mapper.deleteAttributions(paymentId);
        mapper.insertAttribution(command);
        return requirePayment(paymentId).toResponse();
    }

    @Override
    @Transactional(noRollbackFor = FgcBusinessException.class)
    public CommissionPaymentResponse confirm(Long paymentId) {
        ConfirmationData data = requireConfirmationData(paymentId);
        requireDraft(data);
        validateConfirmationRequiredValues(data);

        CapRuleSnapshot rule = mapper.findCapRuleSnapshot(paymentId);
        if (rule == null) {
            rejectWithException(
                    data,
                    "POLICY_MISSING",
                    "HIGH",
                    "확정 검증 정책 누락",
                    "지급 건에 적용할 1,200% 분류정책을 찾을 수 없습니다.",
                    FgcErrorCode.CAP_002,
                    Map.of()
            );
        }

        if (rule.ruleInclusionStatus() != data.inclusionDecisionStatus()
                || rule.ruleInclusionStatus() == InclusionDecisionStatus.REVIEW_REQUIRED) {
            rejectWithException(
                    data,
                    "CAP_REVIEW_REQUIRED",
                    "HIGH",
                    "산입 판단 검토 필요",
                    rule.decisionReason(),
                    FgcErrorCode.CAP_002,
                    Map.of()
            );
        }

        CapCheckCommand check = buildCapCheck(data, rule);
        mapper.insertCapCheck(check);
        mapper.insertCapCheckDetail(check);

        if ("VIOLATION".equals(check.getResultStatus())) {
            rejectWithException(
                    data,
                    "CAP_VIOLATION",
                    "CRITICAL",
                    "1,200% 한도 초과",
                    "후보 지급 건을 포함하면 계약별 한도를 초과합니다.",
                    FgcErrorCode.CAP_001,
                    Map.of("n", check.getUsagePct())
            );
        }

        if ("WARNING".equals(check.getResultStatus())) {
            saveException(
                    data,
                    "CAP_WARNING",
                    "WARNING",
                    "1,200% 한도 경고",
                    "후보 지급 건을 포함한 사용률이 경고 기준 이상입니다."
            );
        }

        if (mapper.confirm(paymentId) != 1) {
            throw new FgcBusinessException(FgcErrorCode.TRAN_005);
        }
        return requirePayment(paymentId).toResponse();
    }

    private CommissionPaymentCommand commandFrom(CommissionPaymentCreateRequest request) {
        return buildCommand(
                null,
                request.sourceBusinessKey(),
                request.contractId(),
                request.agentId(),
                request.commissionItemCode(),
                request.amount(),
                request.attributionMonth(),
                request.scheduledPaymentDate(),
                request.paymentStage(),
                request.attributedContractId(),
                request.inclusionDecisionStatus(),
                request.inclusionDecisionReason(),
                request.allocationPolicyVersion(),
                request.allocationBasis(),
                request.evidenceRef(),
                request.attributionMethod(),
                request.note()
        );
    }

    private CommissionPaymentCommand commandFrom(
            Long paymentId,
            CommissionPaymentUpdateRequest request
    ) {
        return buildCommand(
                paymentId,
                null,
                request.contractId(),
                request.agentId(),
                request.commissionItemCode(),
                request.amount(),
                request.attributionMonth(),
                request.scheduledPaymentDate(),
                request.paymentStage(),
                request.attributedContractId(),
                request.inclusionDecisionStatus(),
                request.inclusionDecisionReason(),
                request.allocationPolicyVersion(),
                request.allocationBasis(),
                request.evidenceRef(),
                request.attributionMethod(),
                request.note()
        );
    }

    private CommissionPaymentCommand buildCommand(
            Long paymentId,
            String sourceBusinessKey,
            Long sourceContractId,
            Long agentId,
            String itemCode,
            BigDecimal amount,
            YearMonth attributionMonth,
            java.time.LocalDate dueDate,
            com.susukkang.fgc.common.code.PaymentStage paymentStage,
            Long requestedAttributedContractId,
            InclusionDecisionStatus inclusionStatus,
            String inclusionReason,
            Long policyVersionId,
            String allocationBasis,
            String evidenceRef,
            AttributionMethod attributionMethod,
            String note
    ) {
        requireAgent(agentId);
        CommissionItemReference item = requireCommissionItem(
                itemCode,
                attributionMonth
        );
        validatePolicyVersion(policyVersionId);

        if (attributionMethod == AttributionMethod.NEWCOMER_NON_CONTRACT) {
            if (inclusionStatus == InclusionDecisionStatus.INCLUDED) {
                invalid("inclusionDecisionStatus", "비계약 선지급 건은 산입 확정 상태로 저장할 수 없습니다.");
            }
            if (!StringUtils.hasText(evidenceRef)) {
                throw new FgcBusinessException(FgcErrorCode.TRAN_004);
            }
        }

        Long attributedContractId = resolveAttributedContract(
                sourceContractId,
                requestedAttributedContractId,
                agentId,
                attributionMonth,
                attributionMethod
        );

        if (inclusionStatus == InclusionDecisionStatus.EXCLUDED
                && !StringUtils.hasText(evidenceRef)) {
            throw new FgcBusinessException(FgcErrorCode.TRAN_004);
        }

        Long allocationPolicyId = resolveAllocationPolicy(
                policyVersionId,
                allocationBasis,
                attributionMethod
        );

        return CommissionPaymentCommand.builder()
                .paymentId(paymentId)
                .sourceBusinessKey(sourceBusinessKey)
                .sourceContractId(sourceContractId)
                .agentId(agentId)
                .commissionItemId(item.commissionItemId())
                .paymentStage(paymentStage)
                .policyVersionId(policyVersionId)
                .settlementMonth(attributionMonth.atDay(1))
                .dueDate(dueDate)
                .amount(amount)
                .cashflowType(item.cashflowType())
                .evidenceRef(evidenceRef)
                .note(note)
                .attributedContractId(attributedContractId)
                .inclusionDecisionStatus(inclusionStatus)
                .inclusionDecisionReason(inclusionReason)
                .attributionMethod(attributionMethod)
                .allocationPolicyId(allocationPolicyId)
                .allocationBasisJson(allocationSnapshot(
                        sourceContractId,
                        allocationBasis,
                        inclusionReason
                ))
                .build();
    }

    private Long resolveAttributedContract(
            Long sourceContractId,
            Long requestedAttributedContractId,
            Long agentId,
            YearMonth attributionMonth,
            AttributionMethod attributionMethod
    ) {
        if (attributionMethod == AttributionMethod.NEWCOMER_NON_CONTRACT) {
            if (requestedAttributedContractId != null) {
                invalid("attributedContractId", "비계약 선지급 건에는 귀속계약을 지정할 수 없습니다.");
            }
            return null;
        }

        Long targetId = requestedAttributedContractId != null
                ? requestedAttributedContractId
                : sourceContractId;
        if (targetId == null) {
            throw new FgcBusinessException(FgcErrorCode.TRAN_002);
        }

        ContractReference target = requireContract(targetId, "attributedContractId");
        if (!target.agentId().equals(agentId)) {
            invalid("attributedContractId", "귀속계약의 설계사가 지급 대상 설계사와 다릅니다.");
        }

        if (sourceContractId != null) {
            requireContract(sourceContractId, "contractId");
        }

        if (attributionMethod == AttributionMethod.SETTLEMENT_SUPPORT_MONTHLY
                && !YearMonth.from(target.contractDate()).equals(attributionMonth)) {
            invalid("attributionMonth", "정착지원금은 지급월의 신계약에 귀속해야 합니다.");
        }

        if (attributionMethod == AttributionMethod.FIRST_CONTRACT_CARRY_FORWARD) {
            boolean firstContract = YearMonth.from(target.contractDate()).equals(attributionMonth)
                    && mapper.countEarlierContracts(
                    agentId,
                    target.contractId(),
                    target.contractDate()
            ) == 0;
            if (!firstContract) {
                invalid("attributedContractId", "이월 선지급분은 최초 신계약 모집월에 귀속해야 합니다.");
            }
        }
        return targetId;
    }

    private Long resolveAllocationPolicy(
            Long policyVersionId,
            String allocationBasis,
            AttributionMethod attributionMethod
    ) {
        if (attributionMethod != AttributionMethod.APPROVED_ALLOCATION) {
            return null;
        }
        if (policyVersionId == null || !StringUtils.hasText(allocationBasis)) {
            invalid("allocationPolicyVersion", "승인 배부에는 정책 버전과 배부기준이 필요합니다.");
        }
        Long allocationPolicyId = mapper.findAllocationPolicyId(
                policyVersionId,
                allocationBasis
        );
        if (allocationPolicyId == null) {
            invalid("allocationBasis", "정책 버전에 해당하는 승인 배부기준이 없습니다.");
        }
        return allocationPolicyId;
    }

    private void validateConfirmationRequiredValues(ConfirmationData data) {
        if (data.attributedAmount() == null || data.contractId() == null) {
            rejectWithException(
                    data,
                    "DATA_QUALITY",
                    "HIGH",
                    "귀속계약 누락",
                    "지급 건을 확정하려면 계약 귀속이 필요합니다.",
                    FgcErrorCode.TRAN_002,
                    Map.of()
            );
        }

        if (data.amount().compareTo(data.attributedAmount()) != 0) {
            BigDecimal difference = data.amount()
                    .subtract(data.attributedAmount())
                    .abs();
            rejectWithException(
                    data,
                    "DATA_QUALITY",
                    "HIGH",
                    "귀속금액 불일치",
                    "귀속금액 합계가 지급액과 다릅니다.",
                    FgcErrorCode.TRAN_003,
                    Map.of(
                            "a", data.attributedAmount(),
                            "b", data.amount(),
                            "c", difference
                    )
            );
        }

        if (data.policyVersionId() == null || !StringUtils.hasText(data.allocationBasis())) {
            rejectWithException(
                    data,
                    "ALLOCATION_EVIDENCE_MISSING",
                    "HIGH",
                    "배부 근거 또는 정책 버전 누락",
                    "확정하려면 배부정책 버전과 배부기준이 필요합니다.",
                    FgcErrorCode.TRAN_004,
                    Map.of()
            );
        }

        if (data.inclusionDecisionStatus() == InclusionDecisionStatus.EXCLUDED
                && !StringUtils.hasText(data.evidenceRef())) {
            rejectWithException(
                    data,
                    "ALLOCATION_EVIDENCE_MISSING",
                    "HIGH",
                    "제외 증빙 누락",
                    "산입 제외 건에는 증빙 참조 정보가 필요합니다.",
                    FgcErrorCode.TRAN_004,
                    Map.of()
            );
        }
    }

    private CapCheckCommand buildCapCheck(
            ConfirmationData data,
            CapRuleSnapshot rule
    ) {
        CapValidationResult result = capValidator.validate(new CapValidationRequest(
                rule.basePremiumAmount(),
                rule.premiumMultiplier(),
                rule.warningUsagePct(),
                rule.existingIncludedAmount(),
                data.attributedAmount(),
                data.inclusionDecisionStatus()
        ));

        return CapCheckCommand.builder()
                .paymentId(data.paymentId())
                .contractId(data.contractId())
                .paymentStage(data.paymentStage().name())
                .capRuleSetId(rule.capRuleSetId())
                .asOfDate(data.attributionMonth())
                .basePremiumAmount(rule.basePremiumAmount())
                .limitAmount(result.limitAmount())
                .includedAmount(result.includedAmount())
                .remainingAmount(result.remainingAmount())
                .usagePct(result.usagePct())
                .resultStatus(result.resultStatus())
                .calculationSnapshotJson(json(Map.of(
                        "existingIncludedAmount", rule.existingIncludedAmount(),
                        "candidateAmount", result.candidateIncludedAmount(),
                        "premiumMultiplier", rule.premiumMultiplier()
                )))
                .commissionItemId(data.commissionItemId())
                .transactionAttributionId(data.transactionAttributionId())
                .classificationSnapshot(data.inclusionDecisionStatus().name())
                .candidateAmount(data.attributedAmount())
                .decisionReason(rule.decisionReason())
                .evidenceRef(data.evidenceRef())
                .build();
    }

    private void rejectWithException(
            ConfirmationData data,
            String exceptionType,
            String severity,
            String title,
            String description,
            FgcErrorCode errorCode,
            Map<String, Object> params
    ) {
        saveException(data, exceptionType, severity, title, description);
        throw new FgcBusinessException(errorCode, params);
    }

    private void saveException(
            ConfirmationData data,
            String exceptionType,
            String severity,
            String title,
            String description
    ) {
        mapper.insertExceptionCase(ExceptionCaseCommand.builder()
                .exceptionKey("PRE_CONFIRM:" + data.paymentId() + ":" + exceptionType)
                .exceptionType(exceptionType)
                .severity(severity)
                .contractId(data.contractId())
                .agentId(data.agentId())
                .policyVersionId(data.policyVersionId())
                .paymentId(data.paymentId())
                .title(title)
                .description(description)
                .build());
    }

    private void requireDraft(ConfirmationData data) {
        if (data.status() != CommissionPaymentStatus.DRAFT) {
            throw new FgcBusinessException(FgcErrorCode.TRAN_005);
        }
    }

    private ConfirmationData requireConfirmationData(Long paymentId) {
        ConfirmationData data = mapper.findConfirmationDataForUpdate(paymentId);
        if (data == null) {
            invalid("paymentId", "지급 건을 찾을 수 없습니다.");
        }
        return data;
    }

    private CommissionPaymentRow requirePayment(Long paymentId) {
        CommissionPaymentRow payment = mapper.findById(paymentId);
        if (payment == null) {
            invalid("paymentId", "지급 건을 찾을 수 없습니다.");
        }
        return payment;
    }

    private void requireAgent(Long agentId) {
        if (!mapper.existsAgent(agentId)) {
            invalid("agentId", "설계사를 찾을 수 없습니다.");
        }
    }

    private ContractReference requireContract(Long contractId, String field) {
        ContractReference contract = mapper.findContract(contractId);
        if (contract == null) {
            invalid(field, "계약을 찾을 수 없습니다.");
        }
        return contract;
    }

    private CommissionItemReference requireCommissionItem(
            String itemCode,
            YearMonth attributionMonth
    ) {
        CommissionItemReference item = mapper.findCommissionItem(
                itemCode,
                attributionMonth.atDay(1)
        );
        if (item == null) {
            invalid("commissionItemCode", "귀속월에 유효한 수수료 항목이 아닙니다.");
        }
        return item;
    }

    private void validatePolicyVersion(Long policyVersionId) {
        if (policyVersionId != null && !mapper.existsPolicyVersion(policyVersionId)) {
            invalid("allocationPolicyVersion", "승인 또는 활성 상태의 정책 버전이 아닙니다.");
        }
    }

    private String allocationSnapshot(
            Long sourceContractId,
            String allocationBasis,
            String inclusionReason
    ) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("sourceContractId", sourceContractId);
        snapshot.put("allocationBasis", allocationBasis);
        snapshot.put("inclusionDecisionReason", inclusionReason);
        return json(snapshot);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("검증 스냅샷 직렬화에 실패했습니다.", exception);
        }
    }

    private void invalid(String field, String detail) {
        throw new FgcBusinessException(
                FgcErrorCode.COMMON_002,
                field,
                Map.of("field", field),
                detail
        );
    }
}
