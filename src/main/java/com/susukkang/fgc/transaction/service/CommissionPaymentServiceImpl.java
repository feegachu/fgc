package com.susukkang.fgc.transaction.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.dto.CapCalculationResult;
import com.susukkang.fgc.cap.dto.CapExceptionCreateCommand;
import com.susukkang.fgc.cap.dto.CapValidationRequest;
import com.susukkang.fgc.cap.dto.CapValidationResult;
import com.susukkang.fgc.cap.service.CapCalculator;
import com.susukkang.fgc.cap.service.CapExceptionService;
import com.susukkang.fgc.cap.service.CapValidator;
import com.susukkang.fgc.common.code.AttributionMethod;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.code.ExclusionType;
import com.susukkang.fgc.common.code.ExceptionSeverity;
import com.susukkang.fgc.common.code.ExceptionType;
import com.susukkang.fgc.common.code.InclusionDecisionStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.util.MoneyUtil;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    private final CapExceptionService capExceptionService;

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
        if (mapper.updateTransaction(prepared.payment()) != 1) {
            throw new FgcBusinessException(FgcErrorCode.TRAN_005);
        }
        mapper.detachPreConfirmDetails(paymentId);
        mapper.deleteAttributions(paymentId);
        persistAttributions(paymentId, prepared.attributions());
        return requirePayment(paymentId);
    }

    @Override
    // 2026-08-10 yslee - 확정 검증 결과와 예외 이력을 지급 건 확정 트랜잭션으로 통합
    // 기존 코드: 잠긴 지급 건을 참조하는 한도 점검을 REQUIRES_NEW 트랜잭션에서 저장
    // 문제: 부모 지급 건의 FOR UPDATE 잠금과 FK 검사가 충돌하고 이슈 #14의 동일 트랜잭션 조건을 위반
    // 개선: 같은 계약 행 잠금과 멱등키를 포함해 점검·예외·상태 처리를 한 트랜잭션에서 수행
    @Transactional(noRollbackFor = CommissionPaymentConfirmationRejectedException.class)
    // FUN-002(#82) — 컨트롤러(@PreAuthorize)를 우회하는 호출 경로가 생겨도 지급 확정만은
    // 서비스 계층에서 한 번 더 막는다. @EnableMethodSecurity는 SecurityConfig에 이미 켜져 있다.
    @PreAuthorize(Roles.CAN_PROCESS)
    public CommissionPaymentResponse confirm(Long paymentId, String idempotencyKey) {
        // 2026-08-11 yslee - 운영정책서 제31조의 확정 게이트 6단계 순서를 코드에 명시
        // 기존 코드: 실제 확정 로직은 정책 순서를 따르지만 단계별 주석이 없어 문서와 코드의 대응 확인이 어려움
        // 문제: 검증 순서가 변경되어도 리뷰 과정에서 정책 위반을 즉시 식별하기 어려움
        // 개선: ① DRAFT 저장 ② 귀속행 입력 ③ 귀속합계=지급액 ④ REVIEW_REQUIRED 없음
        //       ⑤ 1,200% 사전검증 ⑥ CONFIRMED 상태 변경 순서를 고정하고 아래 로직에서 동일하게 수행
        validateIdempotencyKey(idempotencyKey);
        String normalizedIdempotencyKey = normalizeIdempotencyKey(idempotencyKey);
        List<ConfirmationData> attributions = requireConfirmationData(paymentId);
        ConfirmationData first = attributions.get(0);
        if (first.status() == CommissionPaymentStatus.CONFIRMED
                && normalizedIdempotencyKey != null
                && normalizedIdempotencyKey.equals(first.confirmIdempotencyKey())) {
            return requirePayment(paymentId, mapper.findCapCheckIds(paymentId));
        }

        /**
         * @author hjKang
         * @since 2026-08-12
         *
         * 2026-08-12 - 미해결 한도 위반 예외 지급확정 차단
         * 기존 코드: 재검증 결과만으로 지급확정 가능 여부를 판단
         * 문제: 해결조치가 기록되지 않은 기존 한도 위반 예외를 우회할 수 있음
         * 개선: 지급확정 전에 미해결 CAP_VIOLATION 존재 여부를 확인하여 확정을 차단
         */
        if (capExceptionService.hasUnresolvedViolation(paymentId)) {
            throw new CommissionPaymentConfirmationRejectedException(FgcErrorCode.CAP_003);
        }
        requireDraft(attributions.get(0));
        mapper.lockAttributedContracts(paymentId);
        validateConfirmationRequiredValues(attributions);

        List<Long> capCheckIds = new ArrayList<>();

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
                    data.attributionDate(),
                    rule.complianceEvidenceAmount()
            );
            validateRuleConsistency(rule, calculation, data);

            CapValidationResult actualValidation = capValidator.validate(new CapValidationRequest(
                    calculation.limitAmount(),
                    rule.warningUsagePct(),
                    rule.existingIncludedAmount(),
                    data.attributedAmount(),
                    data.inclusionDecisionStatus()
            ));
            CapValidationResult validation = mergeScheduleAndActualValidation(
                    calculation,
                    actualValidation,
                    rule.warningUsagePct()
            );
            CapCheckCommand check = buildCapCheck(data, rule, calculation, validation);
            mapper.insertCapCheck(check);
            mapper.insertCapCheckDetail(check);
            capCheckIds.add(check.getCapCheckId());

            /**
             * @author hjKang
             * @since 2026-08-12
             *
             * 2026-08-12 - FUN-034 한도 예외 생성 서비스 연결
             * 기존 코드: 지급확정 서비스가 WARNING·VIOLATION 예외를 직접 저장
             * 문제: 실시간과 배치가 서로 다른 자연키와 저장 로직을 사용할 수 있음
             * 개선: 최종 한도 판정 결과를 공통 CapExceptionService에 전달하여 멱등 저장
             */
            capExceptionService.createIfNecessary(capExceptionCommand(data, check));

            /**
             * @author hjKang
             * @since 2026-08-12
             *
             * 2026-08-12 - 예외 유형과 심각도 공통 enum 적용
             * 기존 코드: 예외 유형과 심각도를 문자열 리터럴로 전달
             * 문제: DB 허용값 오타를 컴파일 시점에 확인할 수 없음
             * 개선: ExceptionType과 ExceptionSeverity의 name()을 사용하여 DB 코드값을 통일
             */
            if (check.getResultStatus() == CapResultStatus.REVIEW_REQUIRED) {
                rejectWithException(
                        data,
                        ExceptionType.CAP_REVIEW_REQUIRED.name(),
                        ExceptionSeverity.HIGH.name(),
                        "산입 판단 검토 필요",
                        "검토필요 귀속행 또는 준법경영비 증빙을 확인해야 합니다.",
                        FgcErrorCode.CAP_002,
                        Map.of()
                );
            }
            if (check.getResultStatus() == CapResultStatus.VIOLATION) {
                throw new CommissionPaymentConfirmationRejectedException(
                        FgcErrorCode.CAP_001,
                        Map.of("n", check.getUsagePct())
                );
            }
        }

        String capCheckIdsCsv = capCheckIds.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        if (mapper.confirm(paymentId, normalizedIdempotencyKey, capCheckIdsCsv) != 1) {
            throw new FgcBusinessException(FgcErrorCode.TRAN_005);
        }
        return requirePayment(paymentId, capCheckIds);
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
                request.sourceType(),
                request.sourceBusinessKey(),
                request.contractId(),
                request.agentId(),
                request.commissionItemId(),
                request.amount(),
                request.settlementMonth(),
                request.cashflowType(),
                request.scheduledPaymentDate(),
                request.paymentStage(),
                request.allocationPolicyVersion(),
                request.attributions(),
                request.evidenceRef(),
                request.note()
        );
    }

    private PreparedPayment commandFrom(
            Long paymentId,
            CommissionPaymentUpdateRequest request
    ) {
        return buildCommand(
                paymentId,
                request.sourceType(),
                request.sourceBusinessKey(),
                request.contractId(),
                request.agentId(),
                request.commissionItemId(),
                request.amount(),
                request.settlementMonth(),
                request.cashflowType(),
                request.scheduledPaymentDate(),
                request.paymentStage(),
                request.allocationPolicyVersion(),
                request.attributions(),
                request.evidenceRef(),
                request.note()
        );
    }

    private PreparedPayment buildCommand(
            Long paymentId,
            String sourceType,
            String sourceBusinessKey,
            Long sourceContractId,
            Long agentId,
            Long commissionItemId,
            BigDecimal amount,
            LocalDate settlementMonth,
            String cashflowType,
            java.time.LocalDate dueDate,
            com.susukkang.fgc.common.code.PaymentStage paymentStage,
            Long policyVersionId,
            List<CommissionPaymentAttributionRequest> attributionRequests,
            String evidenceRef,
            String note
    ) {
        // 2026-08-11 yslee - 외부 요청값 검증과 원 단위 저장 규칙을 지급 건 생성 경로에 일괄 적용
        // 기존 코드: 항목 코드로 현금흐름을 추론하고 입력 금액·귀속월을 그대로 저장
        // 문제: IF-API-22·23 입력과 저장 스냅샷이 다르며 소수 원 금액과 실제 귀속일이 손실될 수 있음
        // 개선: 요청 항목 ID·현금흐름을 교차 검증하고 지급액·귀속액은 HALF_UP 원 단위로 정규화
        validateSourceType(sourceType);
        validateMonthStart(settlementMonth);
        requireAgent(agentId);
        CommissionItemReference item = requireCommissionItem(
                commissionItemId,
                settlementMonth
        );
        validateCashflowType(cashflowType, item);
        validatePaymentEvidence(evidenceRef, attributionRequests);
        validateAttributionMethodCompatibility(item.itemCode(), attributionRequests);
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
                    settlementMonth,
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
                .sourceType(sourceType)
                .sourceBusinessKey(sourceBusinessKey)
                .sourceContractId(sourceContractId)
                .agentId(agentId)
                .commissionItemId(item.commissionItemId())
                .paymentStage(paymentStage)
                .policyVersionId(policyVersionId)
                .settlementMonth(settlementMonth)
                .dueDate(dueDate)
                .amount(MoneyUtil.roundWon(amount))
                .cashflowType(cashflowType)
                .evidenceRef(evidenceRef)
                .note(note)
                .naturalContractId(naturalContractId)
                .build();
        return new PreparedPayment(payment, attributions);
    }

    // 2026-08-11 yslee - 지급 건 본문의 제외 증빙 참조를 화면·DB 계약에 맞게 검증
    // 기존 코드: 귀속행 evidenceRef만 검증하고 commission_transaction.evidence_ref는 요청·저장에서 누락
    // 문제: TRAN-W02 지급 건 증빙을 입력해도 보존할 수 없고 제외 지급의 원천 근거를 감사에서 추적할 수 없음
    // 개선: 제외 귀속이 하나라도 있으면 지급 건 증빙을 필수화하고 부모·귀속행 증빙을 각각 저장
    private void validatePaymentEvidence(
            String evidenceRef,
            List<CommissionPaymentAttributionRequest> attributionRequests
    ) {
        boolean hasExcludedAttribution = attributionRequests.stream()
                .anyMatch(request -> request.inclusionDecisionStatus() == InclusionDecisionStatus.EXCLUDED);
        if (hasExcludedAttribution && !StringUtils.hasText(evidenceRef)) {
            throw new FgcBusinessException(FgcErrorCode.TRAN_004);
        }
    }

    private CommissionPaymentAttributionCommand buildAttribution(
            Long paymentId,
            int sequence,
            Long sourceContractId,
            Long agentId,
            LocalDate settlementMonth,
            com.susukkang.fgc.common.code.PaymentStage paymentStage,
            Long policyVersionId,
            CommissionPaymentAttributionRequest request
    ) {
        validateAttributionDecision(request, paymentStage);
        Long contractId = resolveAttributedContract(
                sourceContractId,
                request.contractId(),
                agentId,
                settlementMonth,
                request.attributionDate(),
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
                .attributionDate(request.attributionDate())
                .attributionMonth(request.attributionDate().withDayOfMonth(1))
                .amount(MoneyUtil.roundWon(request.amount()))
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
            LocalDate settlementMonth,
            LocalDate attributionDate,
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
                && (!YearMonth.from(target.contractDate()).equals(YearMonth.from(settlementMonth))
                || !YearMonth.from(attributionDate).equals(YearMonth.from(settlementMonth)))) {
            invalid("attributionDate", "정착지원금은 지급월의 신계약에 실제 귀속해야 합니다.");
        }

        if (attributionMethod == AttributionMethod.FIRST_CONTRACT_CARRY_FORWARD) {
            // 2026-08-11 yslee - 위촉 당월 무실적 선지급분을 실제 최초 신계약 모집월로 이월
            // 기존 코드: 대상 계약이 최초 모집월에 속하는지만 확인하여 지급월과 같은 달도 이월 방식으로 허용
            // 문제: REG-20의 "지급월 무실적 후 최초 신계약월 이월" 조건을 재현하지 못하고 귀속방식이 왜곡될 수 있음
            // 개선: 정산월이 위촉월인지, 실제 귀속월이 그보다 뒤인지와 대상월 이전 계약 부재를 함께 확인
            YearMonth attributionMonth = YearMonth.from(attributionDate);
            YearMonth settlementYearMonth = YearMonth.from(settlementMonth);
            YearMonth appointmentMonth = YearMonth.from(
                    mapper.findAgentAppointmentDate(agentId)
            );
            boolean firstContract = YearMonth.from(target.contractDate()).equals(attributionMonth)
                    && mapper.countContractsBeforeMonth(
                    agentId,
                    attributionMonth.atDay(1)
            ) == 0;
            if (!settlementYearMonth.equals(appointmentMonth)
                    || !attributionMonth.isAfter(settlementYearMonth)
                    || !firstContract) {
                invalid("attributedContractId", "위촉월 무실적 선지급분은 이후 최초 신계약 모집월에 귀속해야 합니다.");
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
        // 2026-08-11 yslee - 귀속행이 없는 지급 건은 DRAFT 본문만 저장
        // 기존 코드: 빈 목록도 MyBatis 일괄 INSERT에 전달
        // 문제: 화면에서 귀속 전 임시저장한 DRAFT가 SQL 문법 오류로 실패
        // 개선: 빈 목록이면 상세 INSERT를 생략하고 확정 단계에서 귀속 필수 검증
        if (attributions.isEmpty()) {
            return;
        }
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
        // 2026-08-11 yslee - 배부 방식 전체의 확정 전 배부근거 검증
        // 기존 코드: APPROVED_ALLOCATION만 배부기준을 요구하고 정착지원금 월배부·이월배부는 누락
        // 문제: 배부기준이 없는 정착지원금도 CONFIRMED로 전환되어 REG-20 귀속을 재현할 수 없음
        // 개선: 직접귀속을 제외한 실제 배부 방식은 확정 시 저장된 배부기준을 공통 확인
        boolean allocationBasisRequired = data.attributionMethod() == AttributionMethod.APPROVED_ALLOCATION
                || data.attributionMethod() == AttributionMethod.SETTLEMENT_SUPPORT_MONTHLY
                || data.attributionMethod() == AttributionMethod.FIRST_CONTRACT_CARRY_FORWARD;
        if (allocationBasisRequired && !StringUtils.hasText(data.allocationBasis())) {
            rejectWithException(
                    data,
                    "ALLOCATION_EVIDENCE_MISSING",
                    "HIGH",
                    "배부 근거 누락",
                    "배부 귀속행에는 배부기준이 필요합니다.",
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
        Map<String, Object> snapshot = new LinkedHashMap<>(calculation.calculationSnapshot());
        snapshot.put("scheduledIncludedAmount", calculation.includedAmount());
        snapshot.put("existingIncludedAmount", rule.existingIncludedAmount());
        snapshot.put("candidateAmount", result.candidateIncludedAmount());
        snapshot.put("actualIncludedAmount", rule.existingIncludedAmount()
                .add(result.candidateIncludedAmount()));
        snapshot.put("effectiveIncludedAmount", result.includedAmount());
        snapshot.put("refundRateTableId", calculation.refundRateTableId() == null
                ? "NONE"
                : calculation.refundRateTableId());
        return CapCheckCommand.builder()
                .paymentId(data.paymentId())
                .contractId(data.contractId())
                .paymentStage(data.paymentStage().name())
                .capRuleSetId(rule.capRuleSetId())
                .refundRateTableId(calculation.refundRateTableId())
                .asOfDate(data.attributionDate())
                .basePremiumAmount(calculation.basePremiumAmount())
                .refund12mAmount(calculation.refund12mAmount())
                .complianceDeductionAmount(calculation.complianceDeductionAmount())
                .limitAmount(result.limitAmount())
                .includedAmount(result.includedAmount())
                .remainingAmount(result.remainingAmount())
                .usagePct(result.usagePct())
                .resultStatus(result.resultStatus())
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

    /**
     * 설명 : 지급확정의 최종 한도 판정 결과를 예외 생성 명령으로 변환한다
     *
     * @param data 지급확정 대상 귀속 정보
     * @param check 최종 한도 점검 결과
     * @return 한도 예외 생성 명령
     * @author hjKang
     * @since 2026-08-12
     */
    private CapExceptionCreateCommand capExceptionCommand(
            ConfirmationData data,
            CapCheckCommand check
    ) {
        return CapExceptionCreateCommand.builder()
                .paymentId(data.paymentId())
                .contractId(data.contractId())
                .agentId(data.agentId())
                .policyVersionId(data.policyVersionId())
                .validationRunId(null)
                .paymentStage(data.paymentStage())
                .asOfDate(check.getAsOfDate())
                .capCheckId(check.getCapCheckId())
                .capRuleSetId(check.getCapRuleSetId())
                .refundRateTableId(check.getRefundRateTableId())
                .basePremiumAmount(check.getBasePremiumAmount())
                .refund12mAmount(check.getRefund12mAmount())
                .complianceDeductionAmount(check.getComplianceDeductionAmount())
                .limitAmount(check.getLimitAmount())
                .includedAmount(check.getIncludedAmount())
                .remainingAmount(check.getRemainingAmount())
                .usagePct(check.getUsagePct())
                .resultStatus(check.getResultStatus())
                .calculationSnapshot(check.getCalculationSnapshotJson())
                .build();
    }

    // 2026-08-10 yslee - 지급기준 스케줄과 실제 확정 지급의 두 한도 판정을 결합
    // 기존 코드: CapCalculator의 스케줄 산입액·VIOLATION을 버리고 CONFIRMED 수기 지급액만 확정 게이트에 사용
    // 문제: 지급기준 스케줄이 이미 한도를 초과해도 실제 확정 이력이 적으면 후보 지급 건을 정상 확정할 수 있음
    // 개선: 두 산입액을 중복 합산하지 않고 큰 값을 적용하며 REVIEW_REQUIRED·VIOLATION을 최우선으로 보존
    private CapValidationResult mergeScheduleAndActualValidation(
            CapCalculationResult calculation,
            CapValidationResult actualValidation,
            BigDecimal warningUsagePct
    ) {
        BigDecimal effectiveIncludedAmount = calculation.includedAmount()
                .max(actualValidation.includedAmount());
        BigDecimal remainingAmount = calculation.limitAmount()
                .subtract(effectiveIncludedAmount);
        BigDecimal usagePct = calculation.limitAmount().signum() == 0
                ? (effectiveIncludedAmount.signum() == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(100))
                : MoneyUtil.usagePercent(
                        effectiveIncludedAmount,
                        calculation.limitAmount()
                );

        CapResultStatus resultStatus;
        if (calculation.resultStatus() == CapResultStatus.REVIEW_REQUIRED
                || actualValidation.resultStatus() == CapResultStatus.REVIEW_REQUIRED) {
            resultStatus = CapResultStatus.REVIEW_REQUIRED;
        } else if (calculation.resultStatus() == CapResultStatus.VIOLATION
                || actualValidation.resultStatus() == CapResultStatus.VIOLATION
                || remainingAmount.signum() < 0) {
            resultStatus = CapResultStatus.VIOLATION;
        } else if (calculation.resultStatus() == CapResultStatus.WARNING
                || actualValidation.resultStatus() == CapResultStatus.WARNING
                || usagePct.compareTo(warningUsagePct) >= 0) {
            resultStatus = CapResultStatus.WARNING;
        } else {
            resultStatus = CapResultStatus.NORMAL;
        }

        return new CapValidationResult(
                calculation.limitAmount(),
                actualValidation.candidateIncludedAmount(),
                effectiveIncludedAmount,
                remainingAmount,
                usagePct,
                resultStatus
        );
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
        return requirePayment(paymentId, List.of());
    }

    private CommissionPaymentResponse requirePayment(Long paymentId, List<Long> capCheckIds) {
        CommissionPaymentRow payment = mapper.findById(paymentId);
        if (payment == null) {
            invalid("paymentId", "지급 건을 찾을 수 없습니다.");
        }
        return payment.toResponse(mapper.findAttributions(paymentId), capCheckIds);
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
            Long commissionItemId,
            LocalDate settlementMonth
    ) {
        CommissionItemReference item = mapper.findCommissionItem(
                commissionItemId,
                settlementMonth
        );
        if (item == null) {
            invalid("commissionItemId", "정산월에 유효한 수수료 항목이 아닙니다.");
        }
        return item;
    }

    private void validateSourceType(String sourceType) {
        if (!"GA_MANUAL_PAYMENT".equals(sourceType)) {
            invalid("sourceType", "수기 지급 API는 GA_MANUAL_PAYMENT 원천만 저장할 수 있습니다.");
        }
    }

    private void validateMonthStart(LocalDate settlementMonth) {
        if (settlementMonth.getDayOfMonth() != 1) {
            invalid("settlementMonth", "정산월은 해당 월의 1일(YYYY-MM-01)이어야 합니다.");
        }
    }

    private void validateCashflowType(
            String requestedCashflowType,
            CommissionItemReference item
    ) {
        if (!item.cashflowType().equals(requestedCashflowType)) {
            invalid("cashflowType", "수수료 항목의 지급·차감 구분과 요청값이 다릅니다.");
        }
    }

    // 2026-08-11 yslee - 규제상 전용 귀속방식과 수수료 항목 조합 검증
    // 기존 코드: 귀속방식만 보고 정착지원금·신인활동지원비 규칙을 적용
    // 문제: 기본수수료가 정착지원금 이월로 저장되거나 정착지원금이 일반 직접귀속으로 우회될 수 있음
    // 개선: 항목의 산입 판단은 룰셋에 맡기되 전용 귀속방식은 해당 항목에서만 사용하도록 제한
    private void validateAttributionMethodCompatibility(
            String itemCode,
            List<CommissionPaymentAttributionRequest> attributions
    ) {
        for (CommissionPaymentAttributionRequest attribution : attributions) {
            AttributionMethod method = attribution.attributionMethod();
            boolean settlementMethod = method == AttributionMethod.SETTLEMENT_SUPPORT_MONTHLY
                    || method == AttributionMethod.FIRST_CONTRACT_CARRY_FORWARD;
            if (settlementMethod != "SETTLEMENT_SUPPORT".equals(itemCode)) {
                invalid("attributionMethod", "정착지원금 전용 귀속방식은 SETTLEMENT_SUPPORT 항목에만 사용할 수 있습니다.");
            }

            boolean newcomerMethod = method == AttributionMethod.NEWCOMER_NON_CONTRACT;
            if (newcomerMethod != "NEWCOMER_SUPPORT".equals(itemCode)) {
                invalid("attributionMethod", "신인 비계약 귀속방식은 NEWCOMER_SUPPORT 항목에만 사용할 수 있습니다.");
            }
        }
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey != null
                && (!StringUtils.hasText(idempotencyKey) || idempotencyKey.length() > 160)) {
            invalid("Idempotency-Key", "멱등키는 공백이 아닌 160자 이하 문자열이어야 합니다.");
        }
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {
        return StringUtils.hasText(idempotencyKey) ? idempotencyKey.trim() : null;
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
