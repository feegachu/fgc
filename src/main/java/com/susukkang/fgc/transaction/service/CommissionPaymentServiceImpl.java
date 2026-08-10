package com.susukkang.fgc.transaction.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.dto.CapCalculationResult;
import com.susukkang.fgc.cap.dto.CapValidationRequest;
import com.susukkang.fgc.cap.dto.CapValidationResult;
import com.susukkang.fgc.cap.service.CapCalculator;
import com.susukkang.fgc.cap.service.CapValidator;
import com.susukkang.fgc.common.code.AttributionMethod;
import com.susukkang.fgc.common.code.CapResultStatus;
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

/**
 * 설명 : 수수료 지급 건 등록·수정·확정 서비스
 *
 * @author yslee
 * @since 2026-08-06
 * @version 1.2
 */
@Service
@RequiredArgsConstructor
public class CommissionPaymentServiceImpl implements CommissionPaymentService {

    private final CommissionPaymentMapper mapper;
    private final ObjectMapper objectMapper;
    private final CapValidator capValidator;
    private final CapCalculator capCalculator;

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
    // 2026-08-10 yslee - 확정 검증 결과와 예외 이력을 지급 건 확정 트랜잭션으로 통합
    // 기존 코드: 잠긴 지급 건을 참조하는 한도 점검을 REQUIRES_NEW 트랜잭션에서 저장
    // 문제: 부모 지급 건의 FOR UPDATE 잠금과 FK 검사가 충돌하고 이슈 #14의 동일 트랜잭션 조건을 위반
    // 개선: 확정 거절 예외만 롤백 대상에서 제외하고 점검·예외·상태 처리를 한 트랜잭션에서 수행
    @Transactional(noRollbackFor = CommissionPaymentConfirmationRejectedException.class)
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

        CapCalculationResult calculation = calculateLimit(
                data.contractId(),
                data.paymentStage(),
                data.attributionMonth()
        );
        validateRuleConsistency(rule, calculation, data);

        CapValidationResult validation = capValidator.validate(new CapValidationRequest(
                calculation.limitAmount(),
                rule.warningUsagePct(),
                rule.existingIncludedAmount(),
                data.attributedAmount(),
                data.inclusionDecisionStatus()
        ));
        CapCheckCommand check = buildCapCheck(data, rule, calculation, validation);
        if (check.getResultStatus() == CapResultStatus.VIOLATION) {
            mapper.insertCapCheck(check);
            mapper.insertCapCheckDetail(check);
            mapper.insertExceptionCase(exceptionCommand(
                    data,
                    "CAP_VIOLATION",
                    "CRITICAL",
                    "1,200% 한도 초과",
                    "후보 지급 건을 포함하면 계약별 한도를 초과합니다."
            ));
            throw new CommissionPaymentConfirmationRejectedException(
                    FgcErrorCode.CAP_001,
                    Map.of("n", check.getUsagePct())
            );
        }

        mapper.insertCapCheck(check);
        mapper.insertCapCheckDetail(check);

        if (check.getResultStatus() == CapResultStatus.WARNING) {
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

    private ExceptionCaseCommand exceptionCommand(
            ConfirmationData data,
            String exceptionType,
            String severity,
            String title,
            String description
    ) {
        return ExceptionCaseCommand.builder()
                .exceptionKey("PRE_CONFIRM:" + data.paymentId() + ":" + exceptionType)
                .exceptionType(exceptionType)
                .severity(severity)
                .contractId(data.contractId())
                .agentId(data.agentId())
                .policyVersionId(data.policyVersionId())
                .paymentId(data.paymentId())
                .title(title)
                .description(description)
                .build();
    }

    /*
     * 아래 메서드부터는 지급 건 명령 생성 및 공통 검증 로직이다.
     */

    private CommissionPaymentCommand commandFrom(CommissionPaymentCreateRequest request) {
        return buildCommand(
                null,
                request.sourceBusinessKey(),
                request.paymentSequence(),
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
                request.paymentSequence(),
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
            Integer paymentSequence,
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
                .paymentSequence(paymentSequence)
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
            // 2026-08-07 yslee - 위촉 당월 무실적 선지급분을 최초 신계약 모집월 기준으로 판정
            // 기존 코드: 선택한 계약보다 날짜가 빠른 계약이 있으면 같은 모집월의 계약도 귀속 대상에서 제외
            // 문제: REG-20은 단일 최초 계약이 아니라 최초 신계약 모집월의 신계약에 귀속하도록 규정
            // 개선: 대상월 이전 계약 존재 여부만 확인하여 최초 모집월에 속한 신계약 전체를 허용
            boolean firstContract = YearMonth.from(target.contractDate()).equals(attributionMonth)
                    && mapper.countContractsBeforeMonth(
                    agentId,
                    attributionMonth.atDay(1)
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
            CapRuleSnapshot rule,
            CapCalculationResult calculation,
            CapValidationResult result
    ) {
        return CapCheckCommand.builder()
                .paymentId(data.paymentId())
                .contractId(data.contractId())
                .paymentStage(data.paymentStage().name())
                .capRuleSetId(rule.capRuleSetId())
                .refundRateTableId(calculation.refundRateTableId())
                .asOfDate(data.attributionMonth())
                .basePremiumAmount(calculation.basePremiumAmount())
                .refund12mAmount(calculation.refund12mAmount())
                .complianceDeductionAmount(calculation.complianceDeductionAmount())
                .limitAmount(result.limitAmount())
                .includedAmount(result.includedAmount())
                .remainingAmount(result.remainingAmount())
                .usagePct(result.usagePct())
                .resultStatus(result.resultStatus())
                .calculationSnapshotJson(json(Map.of(
                        "existingIncludedAmount", rule.existingIncludedAmount(),
                        "candidateAmount", result.candidateIncludedAmount(),
                        "premiumMultiplier", rule.premiumMultiplier(),
                        "refundRateTableId", calculation.refundRateTableId() == null
                                ? "NONE"
                                : calculation.refundRateTableId()
                )))
                .commissionItemId(data.commissionItemId())
                .itemCode(data.itemCode())
                .itemName(data.itemName())
                .transactionAttributionId(data.transactionAttributionId())
                .classificationSnapshot(data.inclusionDecisionStatus().name())
                .candidateAmount(data.attributedAmount())
                .decisionReason(rule.decisionReason())
                .evidenceRef(data.evidenceRef())
                .build();
    }

    private CapCalculationResult calculateLimit(
            Long contractId,
            com.susukkang.fgc.common.code.PaymentStage paymentStage,
            java.time.LocalDate asOfDate
    ) {
        return capCalculator.calculate(CapCalculationCommand.realtime(
                contractId,
                paymentStage,
                asOfDate
        ));
    }

    private void validateRuleConsistency(
            CapRuleSnapshot rule,
            CapCalculationResult calculation,
            ConfirmationData data
    ) {
        if (!rule.capRuleSetId().equals(calculation.capRuleSetId())) {
            rejectWithException(
                    data,
                    "CAP_RULE_MISMATCH",
                    "HIGH",
                    "한도 정책 불일치",
                    "지급 정책과 한도 계산 정책이 서로 다릅니다.",
                    FgcErrorCode.CAP_002,
                    Map.of()
            );
        }
        if (calculation.resultStatus() == CapResultStatus.REVIEW_REQUIRED) {
            rejectWithException(
                    data,
                    "CAP_REVIEW_REQUIRED",
                    "HIGH",
                    "한도 계산 검토 필요",
                    "환급률표 또는 산입 분류 근거를 확인해야 합니다.",
                    FgcErrorCode.CAP_002,
                    Map.of()
            );
        }
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
        throw new CommissionPaymentConfirmationRejectedException(errorCode, params);
    }

    private void saveException(
            ConfirmationData data,
            String exceptionType,
            String severity,
            String title,
            String description
    ) {
        mapper.insertExceptionCase(exceptionCommand(
                data,
                exceptionType,
                severity,
                title,
                description
        ));
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
