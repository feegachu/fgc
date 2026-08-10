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
import com.susukkang.fgc.common.code.ExclusionType;
import com.susukkang.fgc.common.code.InclusionDecisionStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.transaction.domain.CapCheckCommand;
import com.susukkang.fgc.transaction.domain.CapRuleSnapshot;
import com.susukkang.fgc.transaction.domain.CommissionItemReference;
import com.susukkang.fgc.transaction.domain.CommissionPaymentAttributionCommand;
import com.susukkang.fgc.transaction.domain.CommissionPaymentCommand;
import com.susukkang.fgc.transaction.domain.CommissionPaymentRow;
import com.susukkang.fgc.transaction.domain.ConfirmationData;
import com.susukkang.fgc.transaction.domain.ContractReference;
import com.susukkang.fgc.transaction.domain.ExceptionCaseCommand;
import com.susukkang.fgc.transaction.dto.CommissionPaymentCreateRequest;
import com.susukkang.fgc.transaction.dto.CommissionPaymentAttributionRequest;
import com.susukkang.fgc.transaction.dto.CommissionPaymentResponse;
import com.susukkang.fgc.transaction.dto.CommissionPaymentUpdateRequest;
import com.susukkang.fgc.transaction.mapper.CommissionPaymentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        PreparedPayment prepared = commandFrom(request);
        mapper.insertTransaction(prepared.payment());
        persistAttributions(prepared.payment().getPaymentId(), prepared.attributions());
        return requirePayment(prepared.payment().getPaymentId());
    }

    @Override
    @Transactional
    public CommissionPaymentResponse update(
            Long paymentId,
            CommissionPaymentUpdateRequest request
    ) {
        List<ConfirmationData> current = requireConfirmationData(paymentId);
        requireDraft(current.get(0));

        PreparedPayment prepared = commandFrom(paymentId, request);
        mapper.updateTransaction(prepared.payment());
        mapper.deleteAttributions(paymentId);
        persistAttributions(paymentId, prepared.attributions());
        return requirePayment(paymentId);
    }

    @Override
    // 2026-08-10 yslee - 확정 검증 결과와 예외 이력을 지급 건 확정 트랜잭션으로 통합
    // 기존 코드: 잠긴 지급 건을 참조하는 한도 점검을 REQUIRES_NEW 트랜잭션에서 저장
    // 문제: 부모 지급 건의 FOR UPDATE 잠금과 FK 검사가 충돌하고 이슈 #14의 동일 트랜잭션 조건을 위반
    // 개선: 확정 거절 예외만 롤백 대상에서 제외하고 점검·예외·상태 처리를 한 트랜잭션에서 수행
    @Transactional(noRollbackFor = CommissionPaymentConfirmationRejectedException.class)
    public CommissionPaymentResponse confirm(Long paymentId) {
        List<ConfirmationData> attributions = requireConfirmationData(paymentId);
        requireDraft(attributions.get(0));
        validateConfirmationRequiredValues(attributions);

        // 2026-08-10 yslee - 지급 건의 모든 계약별 귀속행을 독립 검증
        // 기존 코드: attribution_seq=1인 단일 귀속행만 FUN-033 검증
        // 문제: 다중 계약 배부 시 두 번째 이후 귀속금액이 한도 판정에서 누락
        // 개선: 귀속행별 정책·한도·증빙 공제를 계산하고 하나라도 실패하면 확정을 차단
        for (ConfirmationData data : attributions) {
            validateAttributionForConfirmation(data);
            CapRuleSnapshot rule = requireCapRule(paymentId, data);
            CapCalculationResult calculation = calculateLimit(
                    data.contractId(),
                    data.paymentStage(),
                    data.attributionMonth(),
                    rule.complianceEvidenceAmount()
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
            mapper.insertCapCheck(check);
            mapper.insertCapCheckDetail(check);

            if (check.getResultStatus() == CapResultStatus.REVIEW_REQUIRED) {
                rejectWithException(
                        data,
                        "CAP_REVIEW_REQUIRED",
                        "HIGH",
                        "산입 판단 검토 필요",
                        "검토필요 귀속행 또는 준법경영비 증빙을 확인해야 합니다.",
                        FgcErrorCode.CAP_002,
                        Map.of()
                );
            }
            if (check.getResultStatus() == CapResultStatus.VIOLATION) {
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
            if (check.getResultStatus() == CapResultStatus.WARNING) {
                saveException(
                        data,
                        "CAP_WARNING",
                        "WARNING",
                        "1,200% 한도 경고",
                        "후보 지급 건을 포함한 사용률이 경고 기준 이상입니다."
                );
            }
        }

        if (mapper.confirm(paymentId) != 1) {
            throw new FgcBusinessException(FgcErrorCode.TRAN_005);
        }
        return requirePayment(paymentId);
    }

    private ExceptionCaseCommand exceptionCommand(
            ConfirmationData data,
            String exceptionType,
            String severity,
            String title,
            String description
    ) {
        return ExceptionCaseCommand.builder()
                .exceptionKey("PRE_CONFIRM:" + data.paymentId() + ":"
                        + data.transactionAttributionId() + ":" + exceptionType)
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

    private PreparedPayment commandFrom(CommissionPaymentCreateRequest request) {
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
                request.allocationPolicyVersion(),
                request.attributions(),
                request.note()
        );
    }

    private PreparedPayment commandFrom(
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
                request.allocationPolicyVersion(),
                request.attributions(),
                request.note()
        );
    }

    private PreparedPayment buildCommand(
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
            Long policyVersionId,
            List<CommissionPaymentAttributionRequest> attributionRequests,
            String note
    ) {
        requireAgent(agentId);
        CommissionItemReference item = requireCommissionItem(
                itemCode,
                attributionMonth
        );
        validatePolicyVersion(policyVersionId);
        if (sourceContractId != null) {
            requireContract(sourceContractId, "contractId");
        }

        List<CommissionPaymentAttributionCommand> attributions = new ArrayList<>(attributionRequests.size());
        for (int index = 0; index < attributionRequests.size(); index++) {
            attributions.add(buildAttribution(
                    paymentId,
                    index + 1,
                    sourceContractId,
                    agentId,
                    attributionMonth,
                    paymentStage,
                    policyVersionId,
                    attributionRequests.get(index)
            ));
        }
        Long naturalContractId = sourceContractId != null
                ? sourceContractId
                : attributions.stream()
                .map(CommissionPaymentAttributionCommand::getContractId)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);

        CommissionPaymentCommand payment = CommissionPaymentCommand.builder()
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
                .note(note)
                .naturalContractId(naturalContractId)
                .build();
        return new PreparedPayment(payment, attributions);
    }

    private CommissionPaymentAttributionCommand buildAttribution(
            Long paymentId,
            int sequence,
            Long sourceContractId,
            Long agentId,
            YearMonth attributionMonth,
            com.susukkang.fgc.common.code.PaymentStage paymentStage,
            Long policyVersionId,
            CommissionPaymentAttributionRequest request
    ) {
        validateAttributionDecision(request, paymentStage);
        Long contractId = resolveAttributedContract(
                sourceContractId,
                request.contractId(),
                agentId,
                attributionMonth,
                request.attributionMethod()
        );
        Long allocationPolicyId = resolveAllocationPolicy(
                policyVersionId,
                request.allocationBasis(),
                request.attributionMethod()
        );

        return CommissionPaymentAttributionCommand.builder()
                .paymentId(paymentId)
                .attributionSequence(sequence)
                .agentId(agentId)
                .contractId(contractId)
                .attributionMonth(attributionMonth.atDay(1))
                .amount(request.amount())
                .inclusionDecisionStatus(request.inclusionDecisionStatus())
                .exclusionType(normalizeExclusionType(request.exclusionType()))
                .inclusionDecisionReason(request.inclusionDecisionReason())
                .attributionMethod(request.attributionMethod())
                .allocationPolicyId(allocationPolicyId)
                .allocationBasisJson(allocationSnapshot(
                        sourceContractId,
                        request.allocationBasis(),
                        request.inclusionDecisionReason()
                ))
                .evidenceRef(request.evidenceRef())
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

    private void validateAttributionDecision(
            CommissionPaymentAttributionRequest request,
            com.susukkang.fgc.common.code.PaymentStage paymentStage
    ) {
        ExclusionType exclusionType = normalizeExclusionType(request.exclusionType());
        if (request.attributionMethod() == AttributionMethod.NEWCOMER_NON_CONTRACT) {
            if (request.inclusionDecisionStatus() == InclusionDecisionStatus.INCLUDED) {
                invalid("inclusionDecisionStatus", "비계약 선지급 건은 산입 확정 상태로 저장할 수 없습니다.");
            }
            if (!StringUtils.hasText(request.evidenceRef())) {
                throw new FgcBusinessException(FgcErrorCode.TRAN_004);
            }
        }
        if (request.inclusionDecisionStatus() == InclusionDecisionStatus.EXCLUDED) {
            if (exclusionType == ExclusionType.NONE || !StringUtils.hasText(request.evidenceRef())) {
                throw new FgcBusinessException(FgcErrorCode.TRAN_004);
            }
        } else if (exclusionType != ExclusionType.NONE) {
            invalid("exclusionType", "산입 제외 상태가 아닌 귀속행에는 제외유형을 지정할 수 없습니다.");
        }
        if (exclusionType == ExclusionType.COMPLIANCE_3PCT
                && paymentStage != com.susukkang.fgc.common.code.PaymentStage.INSURER_TO_GA) {
            invalid("exclusionType", "준법경영비 공제는 보험회사→GA 지급단계에만 적용할 수 있습니다.");
        }
        if (request.attributionMethod() == AttributionMethod.NEWCOMER_NON_CONTRACT
                && request.inclusionDecisionStatus() == InclusionDecisionStatus.EXCLUDED
                && exclusionType != ExclusionType.NEW_AGENT_SUPPORT) {
            invalid("exclusionType", "비계약 신인활동지원비는 NEW_AGENT_SUPPORT 유형으로 저장해야 합니다.");
        }
    }

    private ExclusionType normalizeExclusionType(ExclusionType exclusionType) {
        return exclusionType == null ? ExclusionType.NONE : exclusionType;
    }

    private void persistAttributions(
            Long paymentId,
            List<CommissionPaymentAttributionCommand> attributions
    ) {
        for (CommissionPaymentAttributionCommand attribution : attributions) {
            attribution.setPaymentId(paymentId);
        }
        mapper.insertAttributions(attributions);
    }

    private void validateConfirmationRequiredValues(List<ConfirmationData> attributions) {
        ConfirmationData first = attributions.get(0);
        if (first.totalAttributedAmount() == null) {
            rejectWithException(
                    first,
                    "DATA_QUALITY",
                    "HIGH",
                    "귀속정보 누락",
                    "지급 건을 확정하려면 하나 이상의 귀속행이 필요합니다.",
                    FgcErrorCode.TRAN_002,
                    Map.of()
            );
        }

        if (first.amount().compareTo(first.totalAttributedAmount()) != 0) {
            BigDecimal difference = first.amount()
                    .subtract(first.totalAttributedAmount())
                    .abs();
            rejectWithException(
                    first,
                    "DATA_QUALITY",
                    "HIGH",
                    "귀속금액 불일치",
                    "귀속금액 합계가 지급액과 다릅니다.",
                    FgcErrorCode.TRAN_003,
                    Map.of(
                            "a", first.totalAttributedAmount(),
                            "b", first.amount(),
                            "c", difference
                    )
            );
        }

        if (first.policyVersionId() == null) {
            rejectWithException(
                    first,
                    "ALLOCATION_EVIDENCE_MISSING",
                    "HIGH",
                    "정책 버전 누락",
                    "확정하려면 적용 정책 버전이 필요합니다.",
                    FgcErrorCode.TRAN_004,
                    Map.of()
            );
        }
    }

    private void validateAttributionForConfirmation(ConfirmationData data) {
        if (data.attributedAmount() == null
                || (data.contractId() == null
                && data.attributionMethod() != AttributionMethod.NEWCOMER_NON_CONTRACT)) {
            rejectWithException(
                    data,
                    "DATA_QUALITY",
                    "HIGH",
                    "귀속계약 누락",
                    "계약 귀속행에는 귀속계약이 필요합니다.",
                    FgcErrorCode.TRAN_002,
                    Map.of()
            );
        }
        if (data.inclusionDecisionStatus() == InclusionDecisionStatus.REVIEW_REQUIRED
                || data.contractId() == null) {
            rejectWithException(
                    data,
                    "CAP_REVIEW_REQUIRED",
                    "HIGH",
                    "산입 판단 검토 필요",
                    "검토필요 또는 비계약 귀속행은 자동 확정할 수 없습니다.",
                    FgcErrorCode.CAP_002,
                    Map.of()
            );
        }
        if (data.attributionMethod() == AttributionMethod.APPROVED_ALLOCATION
                && !StringUtils.hasText(data.allocationBasis())) {
            rejectWithException(
                    data,
                    "ALLOCATION_EVIDENCE_MISSING",
                    "HIGH",
                    "배부 근거 누락",
                    "승인 배부 귀속행에는 배부기준이 필요합니다.",
                    FgcErrorCode.TRAN_004,
                    Map.of()
            );
        }
        if (data.inclusionDecisionStatus() == InclusionDecisionStatus.EXCLUDED
                && (data.exclusionType() == ExclusionType.NONE
                || !StringUtils.hasText(data.evidenceRef()))) {
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
        // 2026-08-10 yslee - 계산 단계의 검토필요 상태를 최종 점검 결과에 우선 반영
        // 기존 코드: 준법경영비 증빙 누락 시 cap_check 저장 전에 예외를 던져 계산 스냅샷이 남지 않음
        // 문제: 실제 증빙액·최대 허용액·적용액을 감사 시점에 재현할 수 없음
        // 개선: REVIEW_REQUIRED cap_check와 상세 근거를 먼저 저장한 뒤 확정을 차단
        CapResultStatus resultStatus = calculation.resultStatus() == CapResultStatus.REVIEW_REQUIRED
                ? CapResultStatus.REVIEW_REQUIRED
                : result.resultStatus();
        Map<String, Object> snapshot = new LinkedHashMap<>(calculation.calculationSnapshot());
        snapshot.put("existingIncludedAmount", rule.existingIncludedAmount());
        snapshot.put("candidateAmount", result.candidateIncludedAmount());
        snapshot.put("refundRateTableId", calculation.refundRateTableId() == null
                ? "NONE"
                : calculation.refundRateTableId());
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
                .resultStatus(resultStatus)
                .calculationSnapshotJson(json(snapshot))
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
            java.time.LocalDate asOfDate,
            BigDecimal complianceEvidenceAmount
    ) {
        return capCalculator.calculate(CapCalculationCommand.realtime(
                contractId,
                paymentStage,
                asOfDate,
                complianceEvidenceAmount
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
    }

    private CapRuleSnapshot requireCapRule(Long paymentId, ConfirmationData data) {
        CapRuleSnapshot rule = mapper.findCapRuleSnapshot(
                paymentId,
                data.transactionAttributionId()
        );
        if (rule == null) {
            rejectWithException(
                    data,
                    "POLICY_MISSING",
                    "HIGH",
                    "확정 검증 정책 누락",
                    "귀속행에 적용할 1,200% 분류정책을 찾을 수 없습니다.",
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
        return rule;
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

    private List<ConfirmationData> requireConfirmationData(Long paymentId) {
        List<ConfirmationData> data = mapper.findConfirmationDataForUpdate(paymentId);
        if (data == null || data.isEmpty()) {
            invalid("paymentId", "지급 건을 찾을 수 없습니다.");
        }
        return data;
    }

    private CommissionPaymentResponse requirePayment(Long paymentId) {
        CommissionPaymentRow payment = mapper.findById(paymentId);
        if (payment == null) {
            invalid("paymentId", "지급 건을 찾을 수 없습니다.");
        }
        return payment.toResponse(mapper.findAttributions(paymentId));
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

    private record PreparedPayment(
            CommissionPaymentCommand payment,
            List<CommissionPaymentAttributionCommand> attributions
    ) {
    }
}
