package com.susukkang.fgc.transaction.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.audit.service.AuditLogService;
import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.dto.CapCalculationResult;
import com.susukkang.fgc.cap.dto.CapExceptionCreateCommand;
import com.susukkang.fgc.cap.dto.CapValidationRequest;
import com.susukkang.fgc.cap.dto.CapValidationResult;
import com.susukkang.fgc.cap.service.CapCalculator;
import com.susukkang.fgc.cap.service.CapExceptionService;
import com.susukkang.fgc.cap.service.CapValidator;
import com.susukkang.fgc.base.mapper.AgentMapper;
import com.susukkang.fgc.common.code.AgentRankCode;
import com.susukkang.fgc.common.code.AttributionMethod;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.code.ExclusionType;
import com.susukkang.fgc.common.code.ExceptionSeverity;
import com.susukkang.fgc.common.code.ExceptionType;
import com.susukkang.fgc.common.code.InclusionDecisionStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.util.MoneyUtil;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.policy.service.CommissionPolicyService;
import com.susukkang.fgc.transaction.domain.AttributedContractNo;
import com.susukkang.fgc.transaction.domain.CapCheckCommand;
import com.susukkang.fgc.transaction.domain.CapRuleSnapshot;
import com.susukkang.fgc.transaction.domain.CommissionItemReference;
import com.susukkang.fgc.transaction.domain.CommissionPaymentAttributionCommand;
import com.susukkang.fgc.transaction.domain.CommissionPaymentAttributionRow;
import com.susukkang.fgc.transaction.domain.CommissionPaymentCommand;
import com.susukkang.fgc.transaction.domain.CommissionPaymentRow;
import com.susukkang.fgc.transaction.domain.ConfirmationData;
import com.susukkang.fgc.transaction.domain.ContractReference;
import com.susukkang.fgc.transaction.domain.ExceptionCaseCommand;
import com.susukkang.fgc.transaction.dto.*;
import com.susukkang.fgc.transaction.mapper.CommissionPaymentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@RequiredArgsConstructor
public class CommissionPaymentServiceImpl implements CommissionPaymentService {

    @Override
    @Transactional(readOnly = true)
    public CommissionPaymentResponse get(Long paymentId) {
        return requirePayment(paymentId, mapper.findCapCheckIds(paymentId));
    }

    // FUN-061·운영정책서 제51조 "지급 건과 계약귀속" — 등록·수정·확정과 같은 트랜잭션에서 감사행을 남긴다.
    // after JSON 에 지급단계·포함/제외 판단·정착지원금 귀속·배부정책·증빙(REG-20·22)을 함께 담는다.
    private static final String AUDIT_ENTITY_TYPE = "COMMISSION_PAYMENT";
    private static final String AUDIT_PAYMENT_CREATED = "PAYMENT_CREATED";
    private static final String AUDIT_PAYMENT_UPDATED = "PAYMENT_UPDATED";
    private static final String AUDIT_PAYMENT_CONFIRMED = "PAYMENT_CONFIRMED";

    private final CommissionPaymentMapper mapper;
    private final AgentMapper agentMapper;
    private final ObjectMapper objectMapper;
    private final CapValidator capValidator;
    private final CapCalculator capCalculator;
    private final CapExceptionService capExceptionService;
    private final FgcMessageResolver messageResolver;
    private final AuditLogService auditLogService;
    private final CommissionPolicyService commissionPolicyService;

    @Override
    @Transactional
    public CommissionPaymentResponse create(CommissionPaymentCreateRequest request) {
        PreparedPayment prepared = commandFrom(request);
        mapper.insertTransaction(prepared.payment());
        Long paymentId = prepared.payment().getPaymentId();
        persistAttributions(paymentId, prepared.attributions());
        CommissionPaymentRow afterRow = requireRow(paymentId);
        List<CommissionPaymentAttributionRow> afterAttributions = mapper.findAttributions(paymentId);
        auditLogService.record(AuditLogService.AuditEvent.builder()
                .actionCode(AUDIT_PAYMENT_CREATED)
                .entityType(AUDIT_ENTITY_TYPE)
                .entityId(String.valueOf(paymentId))
                .after(paymentAuditValue(afterRow, afterAttributions))
                .policyVersionId(prepared.payment().getPolicyVersionId())
                .build());
        return afterRow.toResponse(afterAttributions, List.of());
    }

    @Override
    @Transactional
    public CommissionPaymentResponse update(
            Long paymentId,
            CommissionPaymentUpdateRequest request
    ) {
        List<ConfirmationData> current = requireConfirmationData(paymentId);
        requireDraft(current.get(0));
        // before 스냅샷 — FOR UPDATE 잠금 이후, 수정 전 본문·귀속행을 after 와 같은 조회
        // DTO(Row)로 확보한다. 두 스냅샷이 같은 스키마여야 diff 화면이 필드 단위로 비교된다.
        CommissionPaymentRow beforeRow = requireRow(paymentId);
        List<CommissionPaymentAttributionRow> beforeAttributions = mapper.findAttributions(paymentId);

        PreparedPayment prepared = commandFrom(paymentId, request);
        if (mapper.updateTransaction(prepared.payment()) != 1) {
            throw new FgcBusinessException(FgcErrorCode.TRAN_005);
        }
        mapper.detachPreConfirmDetails(paymentId);
        mapper.deleteAttributions(paymentId);
        persistAttributions(paymentId, prepared.attributions());
        CommissionPaymentRow afterRow = requireRow(paymentId);
        List<CommissionPaymentAttributionRow> afterAttributions = mapper.findAttributions(paymentId);
        auditLogService.record(AuditLogService.AuditEvent.builder()
                .actionCode(AUDIT_PAYMENT_UPDATED)
                .entityType(AUDIT_ENTITY_TYPE)
                .entityId(String.valueOf(paymentId))
                .before(paymentAuditValue(beforeRow, beforeAttributions))
                .after(paymentAuditValue(afterRow, afterAttributions))
                .policyVersionId(prepared.payment().getPolicyVersionId())
                .build());
        return afterRow.toResponse(afterAttributions, List.of());
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
            failFirst(unresolvedViolationFailure(first));
        }

        requireDraft(attributions.get(0));
        mapper.lockAttributedContracts(paymentId);
        failFirst(requiredValueFailures(attributions));

        List<Long> capCheckIds = new ArrayList<>();
        List<CapCheckCommand> capChecks = new ArrayList<>();

        // 2026-08-10 yslee - 지급 건의 모든 계약별 귀속행을 독립 검증
        // 기존 코드: attribution_seq=1인 단일 귀속행만 FUN-033 검증
        // 문제: 다중 계약 배부 시 두 번째 이후 귀속금액이 한도 판정에서 누락
        // 개선: 귀속행별 정책·한도·증빙 공제를 계산하고 하나라도 실패하면 확정을 차단
        for (ConfirmationData data : attributions) {
            failFirst(attributionFailures(data));
            if (isNonContractNewcomerAttribution(data)) {
                failFirst(newcomerSupportEligibilityFailure(data));
                continue;
            }
            CapRuleSnapshot rule = mapper.findCapRuleSnapshot(
                    paymentId,
                    data.transactionAttributionId()
            );
            boolean applicableCapRuleSetExists = mapper.existsApplicableCapRuleSet(
                    paymentId,
                    data.transactionAttributionId()
            );
            failFirst(capRuleFailure(rule, data, applicableCapRuleSetExists));
            CapCalculationResult calculation = calculateLimit(
                    data.contractId(),
                    data.paymentStage(),
                    data.attributionDate(),
                    rule.complianceEvidenceAmount()
            );
            failFirst(ruleConsistencyFailure(rule, calculation, data));

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
            capChecks.add(check);

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

            failFirst(capResultFailure(data, check));
        }

        String capCheckIdsCsv = capCheckIds.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        if (mapper.confirm(paymentId, normalizedIdempotencyKey, capCheckIdsCsv) != 1) {
            throw new FgcBusinessException(FgcErrorCode.TRAN_005);
        }
        // 확정 거절(CommissionPaymentConfirmationRejectedException)은 여기 도달 전에 던져지므로
        // 감사행이 남지 않는 게 의도다 — 거절 근거는 exception_case 가 보존한다.
        Map<String, Object> confirmedValue = new LinkedHashMap<>();
        confirmedValue.put("status", CommissionPaymentStatus.CONFIRMED);
        confirmedValue.put("idempotencyKey", normalizedIdempotencyKey);
        confirmedValue.put("capChecks", capChecks.stream().map(this::capCheckAuditSummary).toList());
        auditLogService.record(AuditLogService.AuditEvent.builder()
                .actionCode(AUDIT_PAYMENT_CONFIRMED)
                .entityType(AUDIT_ENTITY_TYPE)
                .entityId(String.valueOf(paymentId))
                .before(Map.of("status", CommissionPaymentStatus.DRAFT))
                .after(confirmedValue)
                .policyVersionId(first.policyVersionId())
                .build());
        return requirePayment(paymentId, capCheckIds);
    }

    @Override
    // 2026-08-16 yslee - IF-API-24 지급 전 한도 사전검증 미리보기 (FGC-FUN-033)
    // confirm()과 같은 판정 메서드·계산 엔진(REALTIME)을 재사용하되 아무것도 저장하지 않는다.
    // cap_check·exception_case 기록 생성은 확정(IF-API-25) 경로 전용이며(운영정책서 제31조),
    // confirm은 첫 실패에서 차단하지만 미리보기는 모든 차단 사유를 blockers[]로 수집한다.
    @Transactional(readOnly = true)
    public TransactionPrecheckResponse precheck(Long paymentId) {
        List<ConfirmationData> attributions = requireConfirmationData(paymentId, false);
        ConfirmationData first = attributions.get(0);
        // [의도된 차이] 비DRAFT는 미리보기 대상 자체가 아니라 TRAN_005(409)로 먼저 끊는다.
        // 그래서 "비DRAFT + 미해결 CAP_VIOLATION" 엣지케이스는 confirm=CAP_003(422) / precheck=TRAN_005(409)로
        // 갈리며, 이는 수용한다: precheck에서 CAP_003은 던지지 않고 blockers로 수집하는 사유라 검사 순서를
        // confirm과 맞춰도 비DRAFT 응답은 409 그대로이고, 완전 일치의 유일한 방법(CAP_003을 422로 던지기)은
        // IF-API-24의 "차단 사유는 200 + blockers[]" 계약을 깬다. "판정 일치" 원칙의 범위는 DRAFT 건의
        // 게이트 판정이며, DRAFT 건에서는 두 경로가 같은 판정 메서드로 항상 같은 결과를 낸다.
        requireDraft(first);

        List<GateFailure> failures = new ArrayList<>();
        if (capExceptionService.hasUnresolvedViolation(paymentId)) {
            failures.add(unresolvedViolationFailure(first));
        }
        failures.addAll(requiredValueFailures(attributions));

        List<TransactionPrecheckResponse.CapPreviewItem> previews = new ArrayList<>();
        if (first.totalAttributedAmount() != null) {
            Map<Long, String> contractNos = mapper.findAttributedContractNumbers(paymentId)
                    .stream()
                    .collect(Collectors.toMap(
                            AttributedContractNo::contractId,
                            AttributedContractNo::contractNo
                    ));
            for (ConfirmationData data : attributions) {
                failures.addAll(attributionFailures(data));
                if (isNonContractNewcomerAttribution(data)) {
                    GateFailure newcomerFailure = newcomerSupportEligibilityFailure(data);
                    if (newcomerFailure != null) {
                        failures.add(newcomerFailure);
                    }
                    continue;
                }
                if (data.contractId() == null || data.attributedAmount() == null) {
                    continue;
                }
                CapRuleSnapshot rule = mapper.findCapRuleSnapshot(
                        paymentId,
                        data.transactionAttributionId()
                );
                boolean applicableCapRuleSetExists = mapper.existsApplicableCapRuleSet(
                        paymentId,
                        data.transactionAttributionId()
                );
                GateFailure ruleFailure = capRuleFailure(rule, data, applicableCapRuleSetExists);
                if (ruleFailure != null) {
                    failures.add(ruleFailure);
                }
                if (rule == null) {
                    continue;
                }
                CapCalculationResult calculation = calculateLimit(
                        data.contractId(),
                        data.paymentStage(),
                        data.attributionDate(),
                        rule.complianceEvidenceAmount()
                );
                GateFailure consistencyFailure = ruleConsistencyFailure(rule, calculation, data);
                if (consistencyFailure != null) {
                    failures.add(consistencyFailure);
                }
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
                // 룰 판정 불일치·룰셋 불일치(CAP_002) 행은 confirm이 계산에 도달하지 못하는 행이다.
                // CapValidator는 귀속 스냅샷만 보므로 정책 드리프트 시 NORMAL로 계산될 수 있는데,
                // 게이지 수치는 참고용으로 남기되 판정만은 검토필요로 강제해 "정상" 오인을 막는다.
                if (ruleFailure != null || consistencyFailure != null) {
                    validation = new CapValidationResult(
                            validation.limitAmount(),
                            validation.candidateIncludedAmount(),
                            validation.includedAmount(),
                            validation.remainingAmount(),
                            validation.usagePct(),
                            CapResultStatus.REVIEW_REQUIRED
                    );
                }
                CapCheckCommand check = buildCapCheck(data, rule, calculation, validation);
                previews.add(TransactionPrecheckResponse.CapPreviewItem.from(
                        check,
                        rule,
                        contractNos.get(data.contractId())
                ));
                GateFailure resultFailure = capResultFailure(data, check);
                if (resultFailure != null) {
                    failures.add(resultFailure);
                }
            }
        }
        return new TransactionPrecheckResponse(
                paymentId,
                previews,
                toBlockers(failures),
                failures.isEmpty()
        );
    }

    @Override
    public PageResponse<CommissionPaymentListResponse> search(CommissionPaymentSearchCondition condition, int page, int size) {
        if (page < 1) {
            throw validationException("page", "page는 1 이상이어야 합니다.");
        }
        if (size < 1 || size > 100) {
            throw validationException("size", "size는 1 이상 100 이하여야 합니다.");
        }
        if (condition == null) {
            condition = new CommissionPaymentSearchCondition();
        }
        if (StringUtils.hasText(condition.getSettlementMonth())) {
            try {
                YearMonth.parse(condition.getSettlementMonth());
            } catch (RuntimeException exception) {
                throw validationException("settlementMonth", "정산월은 yyyy-MM 형식이어야 합니다.");
            }
        }

        long offset = (long) (page - 1) * size;
        List<CommissionPaymentListResponse> content = mapper.selectByCondition(condition, size, offset);
        long totalElements = mapper.countByCondition(condition);

        return PageResponse.of(content, page, size, totalElements, "commissionTransactionId,desc");
    }

    private FgcBusinessException validationException(String field, String detail) {
        return new FgcBusinessException(
                FgcErrorCode.COMMON_002,
                field,
                Map.of("field", field),
                detail
        );
    }

    private List<TransactionPrecheckResponse.Blocker> toBlockers(List<GateFailure> failures) {
        return failures.stream()
                .map(failure -> {
                    // CAP_003(미해결 위반)은 hasUnresolvedViolation의 지급 건 단위 판정이라 어느 귀속행이
                    // 원인인지 알 수 없다 — 임의의 첫 귀속행 ID를 노출하면 사용자가 엉뚱한 예외 건을
                    // 찾게 되므로 계약·귀속행 ID를 비운다
                    boolean paymentLevel = failure.errorCode() == FgcErrorCode.CAP_003;
                    return new TransactionPrecheckResponse.Blocker(
                            failure.errorCode().getCode(),
                            messageResolver.resolve(failure.errorCode(), failure.params()),
                            paymentLevel ? null : failure.data().contractId(),
                            paymentLevel ? null : failure.data().transactionAttributionId()
                    );
                })
                // 같은 근본 원인(예: REVIEW_REQUIRED)이 여러 게이트에서 재검출되면 code·message·ID가
                // 전부 같은 Blocker가 반복된다 — record 값 동등성으로 같은 사유는 한 줄로 접는다
                .distinct()
                .toList();
    }

    private ExceptionCaseCommand exceptionCommand(
            ConfirmationData data,
            String exceptionType,
            String reasonCode,
            String severity,
            String title,
            String description
    ) {
        return ExceptionCaseCommand.builder()
                .exceptionKey("PRE_CONFIRM:" + data.paymentId() + ":"
                        + data.transactionAttributionId() + ":" + exceptionType)
                .exceptionType(exceptionType)
                .reasonCode(reasonCode)
                .severity(severity)
                .contractId(data.contractId())
                .agentId(data.agentId())
                .policyVersionId(data.policyVersionId())
                .paymentId(data.paymentId())
                .title(title)
                .description(description)
                .build();
    }

    /**
     * 등록·수정 감사행의 before/after 값 — 지급 본문과 귀속행(포함/제외 판단·귀속방식·배부정책·
     * 증빙) 전체 스냅샷. 두 스냅샷 모두 같은 조회 DTO(Row)를 직렬화하므로 필드 스키마가 항상
     * 일치한다 — 스키마가 다르면 diff 화면이 전 필드를 변경으로 오인한다(화면정의서 :1537).
     */
    private Map<String, Object> paymentAuditValue(
            CommissionPaymentRow payment,
            List<CommissionPaymentAttributionRow> attributions
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("payment", payment);
        value.put("attributions", attributions);
        return value;
    }

    /** 확정 감사행의 1,200% 판정 요약 — 계산 상세는 cap_check.calculation_snapshot 이 보존한다. */
    private Map<String, Object> capCheckAuditSummary(CapCheckCommand check) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("capCheckId", check.getCapCheckId());
        summary.put("contractId", check.getContractId());
        summary.put("paymentStage", check.getPaymentStage());
        summary.put("resultStatus", check.getResultStatus());
        summary.put("usagePct", check.getUsagePct());
        summary.put("limitAmount", check.getLimitAmount());
        summary.put("includedAmount", check.getIncludedAmount());
        return summary;
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
        validateRecipientAgent(paymentStage, agentId);
        CommissionItemReference item = requireCommissionItem(
                commissionItemId,
                settlementMonth
        );
        validateCashflowType(cashflowType, item);
        validatePaymentEvidence(evidenceRef, attributionRequests);
        validateAttributionMethodCompatibility(item.itemCode(), attributionRequests);
        validateAttributionMethodsForPaymentStage(paymentStage, attributionRequests);
        validatePolicyVersion(policyVersionId);
        if (sourceContractId != null) {
            requireContract(sourceContractId, "contractId");
        }

        boolean nonContractNewcomerPayment = !attributionRequests.isEmpty()
                && attributionRequests.stream()
                .allMatch(request -> request.attributionMethod() == AttributionMethod.NEWCOMER_NON_CONTRACT);
        boolean approvedAllocationPayment = attributionRequests.stream()
                .anyMatch(request -> request.attributionMethod() == AttributionMethod.APPROVED_ALLOCATION);
        // 승인배부는 여러 계약에 배부될 수 있다. 귀속행 첫 계약의 계약일을 정책 기준으로 쓰면
        // 요청 배열 순서에 따라 정책 버전이 바뀌므로, 정산월을 기준으로 별도 해석한다.
        Long policyContractId = approvedAllocationPayment
                ? null
                : sourceContractId != null
                ? sourceContractId
                : attributionRequests.stream()
                .map(CommissionPaymentAttributionRequest::contractId)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        Long effectivePolicyVersionId = nonContractNewcomerPayment
                && policyContractId == null
                && policyVersionId == null
                ? null
                : resolvePolicyVersionId(
                        policyVersionId,
                        policyContractId,
                        paymentStage,
                        approvedAllocationPayment,
                        settlementMonth
                );

        List<CommissionPaymentAttributionCommand> attributions = new ArrayList<>(attributionRequests.size());
        for (int index = 0; index < attributionRequests.size(); index++) {
            attributions.add(buildAttribution(
                    paymentId,
                    index + 1,
                    sourceContractId,
                    agentId,
                    settlementMonth,
                    paymentStage,
                    item.commissionItemId(),
                    effectivePolicyVersionId,
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
                .policyVersionId(effectivePolicyVersionId)
                .settlementMonth(settlementMonth)
                .dueDate(dueDate)
                .installmentNo(resolvePaymentInstallmentNo(attributions))
                .amount(MoneyUtil.roundWon(amount))
                .cashflowType(cashflowType)
                .evidenceRef(evidenceRef)
                .note(note)
                .naturalContractId(naturalContractId)
                .build();
        return new PreparedPayment(payment, attributions);
    }

    /**
     * @author hjKang
     * @since 2026-08-16
     *
     * 2026-08-16 - 지급 건의 적용 정책 버전 자동 결정
     * 기존 코드: 화면에서 전달받은 allocationPolicyVersion 값만 검증하고 그대로 저장했다.
     * 문제: 지급 등록 화면이 정책 버전을 전달하지 않으면 지급 건과 귀속행의 정책 버전이 null로 저장되어 사전검증이 증빙 누락으로 잘못 차단되었다.
     * 개선: 요청 정책 버전이 없으면 지급 건 또는 귀속행의 계약과 지급단계를 기준으로 현행 수수료 정책 버전을 조회하여 저장한다.
     *       다만 정책을 찾지 못한 것은 DRAFT 저장을 막을 사유가 아니다. 정책 버전 없이 초안을
     *       보존하고, 확정 게이트가 POLICY_VERSION_MISSING으로 확정을 차단한다.
     */
    private Long resolvePolicyVersionId(
            Long requestedPolicyVersionId,
            Long contractId,
            com.susukkang.fgc.common.code.PaymentStage paymentStage,
            boolean approvedAllocationPayment,
            LocalDate settlementMonth
    ) {
        if (requestedPolicyVersionId != null) {
            validatePolicyVersion(requestedPolicyVersionId);
            return requestedPolicyVersionId;
        }
        try {
            Long resolvedPolicyVersionId;
            if (approvedAllocationPayment) {
                resolvedPolicyVersionId = commissionPolicyService
                        .resolveCurrentAllocationPolicyVersion(settlementMonth);
            } else {
                if (contractId == null) {
                    // 귀속 전 DRAFT에는 정책을 고를 계약이 없을 수 있다.
                    // 저장은 허용하고 확정 게이트(POLICY_VERSION_MISSING)에서 차단한다.
                    return null;
                }
                resolvedPolicyVersionId = commissionPolicyService
                        .resolveCurrentCommission(contractId, paymentStage)
                        .getPolicyVersionId();
            }
            validatePolicyVersion(resolvedPolicyVersionId);
            return resolvedPolicyVersionId;
        } catch (FgcBusinessException exception) {
            if (isAutomaticPolicyResolutionFailure(exception)) {
                return null;
            }
            throw exception;
        }
    }

    private boolean isAutomaticPolicyResolutionFailure(FgcBusinessException exception) {
        return "commissionPolicy".equals(exception.getField())
                || "commissionRules".equals(exception.getField())
                || "allocationPolicyVersion".equals(exception.getField());
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
            Long commissionItemId,
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
                paymentStage,
                request.attributionMethod()
        );
        Long allocationPolicyId = resolveAllocationPolicy(
                policyVersionId,
                request.allocationBasis(),
                request.attributionMethod()
        );
        // 수취인까지 좁혀야 관리자수수료(팀장·지사장·본부장 3행)에서 올바른 스케줄 행이 잡힌다.
        // 귀속행 설계사(resolveAttributionAgentId)가 아니라 지급 건의 수취인을 넘긴다 —
        // 원수사→GA 는 수취인이 없어 null 이고, 스케줄의 beneficiary_agent_id 도 null 이라 짝이 맞는다.
        Long scheduleLineId = resolveOperationalScheduleLineId(
                contractId,
                paymentStage,
                commissionItemId,
                agentId,
                request.attributionDate()
        );
        Integer installmentNo = resolveOperationalScheduleInstallmentNo(
                contractId,
                paymentStage,
                commissionItemId,
                agentId,
                request.attributionDate()
        );

        return CommissionPaymentAttributionCommand.builder()
                .paymentId(paymentId)
                .agentId(resolveAttributionAgentId(agentId, contractId))
                .attributionSequence(sequence)
                .contractId(contractId)
                .scheduleLineId(scheduleLineId)
                .installmentNo(installmentNo)
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
                        request.inclusionDecisionReason(),
                        request.attributionMethod() == AttributionMethod.APPROVED_ALLOCATION
                                && policyVersionId == null
                ))
                .evidenceRef(request.evidenceRef())
                .build();
    }

    private Long resolveOperationalScheduleLineId(
            Long contractId,
            PaymentStage paymentStage,
            Long commissionItemId,
            Long recipientAgentId,
            LocalDate attributionDate
    ) {
        if (contractId == null || attributionDate == null) {
            return null;
        }
        LocalDate attributionMonthStart = attributionDate.withDayOfMonth(1);
        List<Long> ids = mapper.findOperationalScheduleLineIds(
                contractId, paymentStage, commissionItemId, recipientAgentId,
                attributionMonthStart, attributionMonthStart.plusMonths(1));
        return ids != null && ids.size() == 1 ? ids.get(0) : null;
    }

    /**
     * @author hjKang
     * @since 2026-08-19
     *
     * 2026-08-19 - FUN-048 대사 회차 비교를 위한 실제 지급 건 회차 자동 결정
     * 기존 코드: 귀속행에 schedule_line_id만 연결하고 commission_transaction.installment_no는 항상 null로 남았다.
     * 문제: 대사 매처가 예상·실제가 모두 있는 그룹에서도 회차를 비교할 수 없어 REVIEW_REQUIRED로 떨어진다.
     * 개선: schedule_line_id를 찾을 때와 같은 조건으로 회차를 읽어 지급 건에 스냅샷으로 보존한다.
     *       관리자수수료처럼 같은 항목·같은 월에 수취인만 다른 행이 여럿이어도 회차가 하나면 확정한다.
     */
    private Integer resolveOperationalScheduleInstallmentNo(
            Long contractId,
            PaymentStage paymentStage,
            Long commissionItemId,
            Long recipientAgentId,
            LocalDate attributionDate
    ) {
        if (contractId == null || attributionDate == null) {
            return null;
        }
        LocalDate attributionMonthStart = attributionDate.withDayOfMonth(1);
        List<Integer> installmentNos = mapper.findOperationalScheduleInstallmentNos(
                contractId, paymentStage, commissionItemId, recipientAgentId,
                attributionMonthStart, attributionMonthStart.plusMonths(1));
        if (installmentNos == null) {
            return null;
        }
        List<Integer> distinct = installmentNos.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        return distinct.size() == 1 ? distinct.get(0) : null;
    }

    /**
     * 지급 건 1건에 귀속행이 여럿이면(다중 계약 배부) 회차가 하나로 수렴할 때만 부모에 남긴다.
     * 하나라도 결정하지 못했으면 null로 둔다 — 대사에서 REVIEW_REQUIRED 검토 대상이 된다
     * (V18__commission_transaction_installment_no.sql 의 컬럼 주석과 같은 계약).
     */
    private static Integer resolvePaymentInstallmentNo(
            List<CommissionPaymentAttributionCommand> attributions
    ) {
        if (attributions.isEmpty()) {
            return null;
        }
        boolean allResolved = attributions.stream()
                .allMatch(attribution -> attribution.getInstallmentNo() != null);
        if (!allResolved) {
            return null;
        }
        List<Integer> distinct = attributions.stream()
                .map(CommissionPaymentAttributionCommand::getInstallmentNo)
                .distinct()
                .toList();
        return distinct.size() == 1 ? distinct.get(0) : null;
    }

    private Long resolveAttributedContract(
            Long sourceContractId,
            Long requestedAttributedContractId,
            Long agentId,
            LocalDate settlementMonth,
            LocalDate attributionDate,
            PaymentStage paymentStage,
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
        if (paymentStage == PaymentStage.GA_TO_FC && !target.agentId().equals(agentId)) {
            // 2026-08-19 hjKang - FGC-FUN-065 관리자수수료 수취인 허용
            // 기존 코드: GA→FC 지급 건의 수취인이 귀속계약의 모집설계사와 다르면 무조건 거부했다.
            // 문제: 관리자수수료는 팀장·지사장·본부장이 받는 돈이고(운영정책서 제20조 패턴 GA-LIFE-A),
            //       이들은 그 계약을 모집한 적이 없어 등록 자체가 불가능했다.
            //       V3 시드는 SQL로 직접 넣어 이 검증을 우회하므로, API가 자기 시드 데이터를
            //       재현하지 못하는 상태였다.
            // 개선: 모집설계사가 아니면 곧바로 거부하지 않고, 계약일 기준 그 계약 조직 계층에서
            //       수취인 직급의 활성 관리자를 찾아 일치할 때만 통과시킨다. 아무 팀장이나 되는 게
            //       아니라 그 계약 조직의 팀장만 통과한다 —
            //       ScheduleService.resolveBeneficiaryAgentId 와 같은 판정을 재사용한다.
            requireManagerOfContractOrganization(target, agentId);
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

    /**
     * @author hjKang
     * @since 2026-08-19
     *
     * 2026-08-19 - 원수사→GA 귀속행의 설계사 자동 결정
     * 기존 코드: 귀속행 agent_id 에 지급 건의 수령 설계사를 그대로 넣었다.
     * 문제: 원수사→GA 지급 건은 받는 주체가 GA 법인이라 수령 설계사를 지정할 수 없고
     *       (validateRecipientAgent · DB ck_transaction_recipient), 그 결과 귀속행 agent_id 가
     *       항상 null 이 되었다. InsurerGaReconciliationMapper 는 원수사 설계사코드가 없으면
     *       ta.agent_id 를 실제 설계사로 쓰므로, 수기 등록 건은 설계사를 식별하지 못해
     *       금액이 정확히 일치해도 REVIEW_REQUIRED 로 빠졌다.
     * 개선: V3 시드의 COALESCE(수취인, 계약 모집설계사)와 같은 규칙을 적용한다.
     *
     * transaction_attribution.agent_id 는 지급단계에 따라 뜻이 다르다.
     *   GA_TO_FC        : 이 돈을 받은 사람(수취인)
     *   INSURER_TO_GA   : 이 계약을 모집한 사람(수취인이 아니다)
     * 원수사→GA 대사는 원수사 명세의 설계사코드 매핑 결과가 계약 모집설계사와 같은지를
     * 검증하는 것이므로(시드명세 REC-06) 모집설계사를 남기는 것이 맞다.
     */
    private Long resolveAttributionAgentId(Long agentId, Long contractId) {
        if (agentId != null || contractId == null) {
            return agentId;
        }
        ContractReference contract = mapper.findContract(contractId);
        return contract == null ? null : contract.agentId();
    }

    private Long resolveAllocationPolicy(
            Long policyVersionId,
            String allocationBasis,
            AttributionMethod attributionMethod
    ) {
        if (attributionMethod != AttributionMethod.APPROVED_ALLOCATION) {
            return null;
        }
        if (!StringUtils.hasText(allocationBasis)) {
            invalid("allocationBasis", "승인 배부에는 배부기준이 필요합니다.");
        }
        if (policyVersionId == null) {
            // 정책 미비 초안은 보존하고, confirm()의 정책 버전 게이트에서 확정을 막는다.
            return null;
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
            if (paymentStage == PaymentStage.INSURER_TO_GA) {
                invalid("attributionMethod", "원수사→GA 지급 건에는 신인 비계약 귀속을 사용할 수 없습니다.");
            }
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

    /**
     * 확정 게이트 판정 실패 서술자. confirm()은 첫 실패에서 예외 이력을 남기고 차단하며(failFirst),
     * precheck는 같은 판정 메서드의 결과를 저장 없이 전부 수집한다 — 두 경로의 판정 일치 보장.
     * exceptionType이 null이면 exception_case를 저장하지 않고 차단만 한다 (CAP_001·CAP_003 경로).
     */
    private record GateFailure(
            ConfirmationData data,
            String exceptionType,
            String reasonCode,
            String severity,
            String title,
            String description,
            FgcErrorCode errorCode,
            Map<String, Object> params
    ) {
        // reasonCode 없이 부르는 기존 호출부(대부분의 게이트)는 exceptionType이 곧 사유라
        // reasonCode를 별도로 안 남긴다 — CAP_RULE_MISMATCH처럼 exceptionType 자체가
        // 흡수·재분류된 경우에만 8-인자 정식 생성자로 reasonCode를 명시한다.
        GateFailure(
                ConfirmationData data,
                String exceptionType,
                String severity,
                String title,
                String description,
                FgcErrorCode errorCode,
                Map<String, Object> params
        ) {
            this(data, exceptionType, null, severity, title, description, errorCode, params);
        }
    }

    private void failFirst(GateFailure failure) {
        if (failure != null) {
            failFirst(List.of(failure));
        }
    }

    private void failFirst(List<GateFailure> failures) {
        if (failures.isEmpty()) {
            return;
        }
        GateFailure failure = failures.get(0);
        if (failure.exceptionType() != null) {
            saveException(
                    failure.data(),
                    failure.exceptionType(),
                    failure.reasonCode(),
                    failure.severity(),
                    failure.title(),
                    failure.description()
            );
        }
        throw new CommissionPaymentConfirmationRejectedException(
                failure.errorCode(),
                failure.params()
        );
    }

    private GateFailure unresolvedViolationFailure(ConfirmationData first) {
        return new GateFailure(first, null, null, null, null, FgcErrorCode.CAP_003, Map.of());
    }

    private List<GateFailure> requiredValueFailures(List<ConfirmationData> attributions) {
        List<GateFailure> failures = new ArrayList<>();
        ConfirmationData first = attributions.get(0);
        if (first.totalAttributedAmount() == null) {
            failures.add(new GateFailure(
                    first,
                    "DATA_QUALITY",
                    "HIGH",
                    "귀속정보 누락",
                    "지급 건을 확정하려면 하나 이상의 귀속행이 필요합니다.",
                    FgcErrorCode.TRAN_002,
                    Map.of()
            ));
        } else if (first.amount().compareTo(first.totalAttributedAmount()) != 0) {
            BigDecimal difference = first.amount()
                    .subtract(first.totalAttributedAmount())
                    .abs();
            failures.add(new GateFailure(
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
            ));
        }

        /*
         * @author hjKang
         * @since 2026-08-16
         *
         * 2026-08-16 - 정책 버전 누락과 증빙 누락 오류 구분
         * 기존 코드: 정책 버전 누락에도 증빙 필수 오류(TRAN_004)를 반환했다.
         * 문제: 산입 귀속행처럼 증빙이 필요 없는 건도 증빙 누락으로 안내되어 실제 차단 원인을 확인할 수 없었다.
         * 개선: 정책 버전 누락을 지급 확정 전용 업무 오류(TRAN_007)로 안내한다.
         */
        if (first.policyVersionId() == null && !isNonContractNewcomerAttribution(first)) {
            failures.add(new GateFailure(
                    first,
                    "POLICY_VERSION_MISSING",
                    "HIGH",
                    "정책 버전 누락",
                    "확정하려면 적용 정책 버전이 필요합니다.",
                    FgcErrorCode.TRAN_007,
                    Map.of()
            ));
        }
        return failures;
    }

    private List<GateFailure> attributionFailures(ConfirmationData data) {
        List<GateFailure> failures = new ArrayList<>();
        boolean nonContractNewcomer = isNonContractNewcomerAttribution(data);
        if (data.attributedAmount() == null
                || (data.contractId() == null
                && !nonContractNewcomer)) {
            failures.add(new GateFailure(
                    data,
                    "DATA_QUALITY",
                    "HIGH",
                    "귀속계약 누락",
                    "계약 귀속행에는 귀속계약이 필요합니다.",
                    FgcErrorCode.TRAN_002,
                    Map.of()
            ));
        }
        if (data.inclusionDecisionStatus() == InclusionDecisionStatus.REVIEW_REQUIRED
                || (data.contractId() == null && !nonContractNewcomer)) {
            failures.add(new GateFailure(
                    data,
                    "CAP_REVIEW_REQUIRED",
                    "HIGH",
                    "산입 판단 검토 필요",
                    "검토필요 또는 비계약 귀속행은 자동 확정할 수 없습니다.",
                    FgcErrorCode.CAP_002,
                    Map.of()
            ));
        }
        if (!nonContractNewcomer
                && data.contractDate() != null
                && data.attributionDate() != null
                && data.attributionDate().isBefore(data.contractDate())) {
            failures.add(new GateFailure(
                    data,
                    "DATA_QUALITY",
                    "HIGH",
                    "계약일 전 귀속",
                    "귀속일은 계약일 이후여야 합니다. 계약일: " + data.contractDate()
                            + ", 귀속일: " + data.attributionDate(),
                    FgcErrorCode.TRAN_008,
                    Map.of()
            ));
        }
        // 2026-08-11 yslee - 배부 방식 전체의 확정 전 배부근거 검증
        // 기존 코드: APPROVED_ALLOCATION만 배부기준을 요구하고 정착지원금 월배부·이월배부는 누락
        // 문제: 배부기준이 없는 정착지원금도 CONFIRMED로 전환되어 REG-20 귀속을 재현할 수 없음
        // 개선: 직접귀속을 제외한 실제 배부 방식은 확정 시 저장된 배부기준을 공통 확인
        boolean allocationBasisRequired = data.attributionMethod() == AttributionMethod.APPROVED_ALLOCATION
                || data.attributionMethod() == AttributionMethod.SETTLEMENT_SUPPORT_MONTHLY
                || data.attributionMethod() == AttributionMethod.FIRST_CONTRACT_CARRY_FORWARD;
        if (allocationBasisRequired && !StringUtils.hasText(data.allocationBasis())) {
            failures.add(new GateFailure(
                    data,
                    "ALLOCATION_EVIDENCE_MISSING",
                    "HIGH",
                    "배부 근거 누락",
                    "배부 귀속행에는 배부기준이 필요합니다.",
                    FgcErrorCode.TRAN_004,
                    Map.of()
            ));
        }
        if (data.inclusionDecisionStatus() == InclusionDecisionStatus.EXCLUDED
                && (data.exclusionType() == ExclusionType.NONE
                || !StringUtils.hasText(data.evidenceRef()))) {
            failures.add(new GateFailure(
                    data,
                    "ALLOCATION_EVIDENCE_MISSING",
                    "HIGH",
                    "제외 증빙 누락",
                    "산입 제외 건에는 증빙 참조 정보가 필요합니다.",
                    FgcErrorCode.TRAN_004,
                    Map.of()
            ));
        }
        return failures;
    }

    private boolean isNonContractNewcomerAttribution(ConfirmationData data) {
        return data.contractId() == null
                && data.attributionMethod() == AttributionMethod.NEWCOMER_NON_CONTRACT;
    }

    /**
     * 계약 미귀속 신인활동지원비는 1,200% 금액 한도에는 산입하지 않는다. 다만 REG-21의
     * 제외 적격성(직전 3년 무경력·지원 가능 기간)을 확정 시점에 확인해야 한다.
     */
    private GateFailure newcomerSupportEligibilityFailure(ConfirmationData data) {
        if (mapper.existsEligibleNewcomerSupportAgent(data.agentId(), data.attributionDate())) {
            return null;
        }
        return new GateFailure(
                data,
                "NEWCOMER_SUPPORT_REVIEW",
                "HIGH",
                "신인활동지원 적격성 확인 필요",
                "신인 지원 대상 또는 지원 가능 기간을 확인할 수 없어 지급 확정을 차단했습니다.",
                FgcErrorCode.CAP_002,
                Map.of()
        );
    }

    // 2026-08-12 hjKang - 예외 유형과 심각도 공통 enum 적용 (ExceptionType·ExceptionSeverity의 name() 사용)
    private GateFailure capResultFailure(ConfirmationData data, CapCheckCommand check) {
        if (check.getResultStatus() == CapResultStatus.REVIEW_REQUIRED) {
            return new GateFailure(
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
            return new GateFailure(
                    data, null, null, null, null,
                    FgcErrorCode.CAP_001,
                    Map.of("n", check.getUsagePct())
            );
        }
        return null;
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

    private GateFailure ruleConsistencyFailure(
            CapRuleSnapshot rule,
            CapCalculationResult calculation,
            ConfirmationData data
    ) {
        if (!rule.capRuleSetId().equals(calculation.capRuleSetId())) {
            // "CAP_RULE_MISMATCH"는 V23_1(exception_case_exception_type_check 재정의)에서
            // exception_type 허용 목록에서 빠지고 CAP_REVIEW_REQUIRED로 흡수됐다 — 그 값을
            // exceptionType으로 계속 쓰면 CHECK 제약 위반으로 저장 자체가 실패한다.
            // 세부 사유는 reason_code로 남겨서 "정책 불일치"라는 원래 원인이 사라지지
            // 않게 한다(코드리뷰 반영). exceptionKey가 exceptionType을 그대로 물고 있어
            // (PRE_CONFIRM:{paymentId}:{attributionId}:{exceptionType}) 예전
            // CAP_RULE_MISMATCH 키와는 여전히 다르다 — 이 지급 건이 과거에 이미
            // CAP_RULE_MISMATCH로 기록된 적이 있어도 새 실행에서 재사용되지 않고 새
            // 업무건이 생긴다는 뜻이다. exceptionKey를 exceptionType과 분리해 안정키로
            // 바꾸는 건 이 세 게이트 실패 유형 전체(TRAN_002 등)에 걸친 별도 설계
            // 결정이 필요해 이번 수정 범위에 넣지 않았다.
            return new GateFailure(
                    data,
                    "CAP_REVIEW_REQUIRED",
                    "CAP_RULE_MISMATCH",
                    "HIGH",
                    "한도 정책 불일치",
                    "지급 적용 한도정책 ID=" + rule.capRuleSetId()
                            + ", 계산 적용 한도정책 ID=" + calculation.capRuleSetId()
                            + "로 서로 다릅니다. 정책 버전과 적용 기준일을 정정한 뒤 재검증하세요.",
                    FgcErrorCode.CAP_002,
                    Map.of()
            );
        }
        return null;
    }

    private GateFailure capRuleFailure(
            CapRuleSnapshot rule,
            ConfirmationData data,
            boolean applicableCapRuleSetExists
    ) {
        if (rule == null) {
            boolean missingRuleSet = !applicableCapRuleSetExists;
            return new GateFailure(
                    data,
                    missingRuleSet ? "POLICY_MISSING" : "CAP_ITEM_UNCLASSIFIED",
                    "HIGH",
                    missingRuleSet ? "1,200% 룰셋 누락" : "한도 산입 항목 미분류",
                    missingRuleSet
                            ? "적용 가능한 활성 1,200% 룰셋이 없습니다."
                            : "적용 룰셋에 해당 수수료 항목의 산입 기준이 없습니다.",
                    missingRuleSet ? FgcErrorCode.CAP_004 : FgcErrorCode.CAP_002,
                    Map.of()
            );
        }
        if (rule.ruleInclusionStatus() != data.inclusionDecisionStatus()
                || rule.ruleInclusionStatus() == InclusionDecisionStatus.REVIEW_REQUIRED) {
            return new GateFailure(
                    data,
                    "CAP_REVIEW_REQUIRED",
                    "HIGH",
                    "산입 판단 검토 필요",
                    rule.decisionReason(),
                    FgcErrorCode.CAP_002,
                    Map.of()
            );
        }
        return null;
    }

    private void saveException(
            ConfirmationData data,
            String exceptionType,
            String reasonCode,
            String severity,
            String title,
            String description
    ) {
        mapper.insertExceptionCase(exceptionCommand(
                data,
                exceptionType,
                reasonCode,
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
        return requireConfirmationData(paymentId, true);
    }

    private List<ConfirmationData> requireConfirmationData(Long paymentId, boolean forUpdate) {
        List<ConfirmationData> data = forUpdate
                ? mapper.findConfirmationDataForUpdate(paymentId)
                : mapper.findConfirmationData(paymentId);
        if (data == null || data.isEmpty()) {
            invalid("paymentId", "지급 건을 찾을 수 없습니다.");
        }
        return data;
    }

    private CommissionPaymentResponse requirePayment(Long paymentId) {
        return requirePayment(paymentId, List.of());
    }

    private CommissionPaymentResponse requirePayment(Long paymentId, List<Long> capCheckIds) {
        return requireRow(paymentId).toResponse(mapper.findAttributions(paymentId), capCheckIds);
    }

    private CommissionPaymentRow requireRow(Long paymentId) {
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

    private void validateRecipientAgent(PaymentStage paymentStage, Long agentId) {
        if (paymentStage == PaymentStage.GA_TO_FC) {
            requireAgent(agentId);
            return;
        }
        if (agentId != null) {
            invalid("agentId", "원수사→GA 지급 건에는 수령 설계사를 지정할 수 없습니다.");
        }
    }

    private void validateAttributionMethodsForPaymentStage(
            PaymentStage paymentStage,
            List<CommissionPaymentAttributionRequest> attributions
    ) {
        if (paymentStage != PaymentStage.INSURER_TO_GA) {
            return;
        }
        for (CommissionPaymentAttributionRequest attribution : attributions) {
            if (attribution.attributionMethod() == AttributionMethod.SETTLEMENT_SUPPORT_MONTHLY
                    || attribution.attributionMethod() == AttributionMethod.FIRST_CONTRACT_CARRY_FORWARD
                    || attribution.attributionMethod() == AttributionMethod.NEWCOMER_NON_CONTRACT) {
                invalid("attributionMethod", "원수사→GA 지급 건에는 설계사 지원 귀속방식을 사용할 수 없습니다.");
            }
        }
    }

    /**
     * @author hjKang
     * @since 2026-08-19
     *
     * 수취인이 귀속계약 조직의 정당한 관리자인지 확인한다.
     * 계약일 기준으로 계약 소속 조직에서 상위 조직으로 올라가며 수취인 직급의 활성 설계사를 찾고,
     * 그 결과가 수취인과 같을 때만 통과시킨다(AgentMapper.findActiveAgentIdFromOrganizationHierarchy —
     * ScheduleService 가 예상 스케줄의 관리자수수료 수취인을 정할 때 쓰는 것과 같은 판정).
     * 직급이 FC 이면 모집설계사 본인이어야 하므로 허용하지 않는다.
     */
    private void requireManagerOfContractOrganization(ContractReference target, Long agentId) {
        AgentRankCode rankCode = mapper.findAgentRankCode(agentId);
        if (rankCode == null || rankCode == AgentRankCode.FC || target.organizationId() == null) {
            invalid("attributedContractId", "귀속계약의 설계사가 지급 대상 설계사와 다릅니다.");
            return;
        }
        Long expectedManagerId = agentMapper.findActiveAgentIdFromOrganizationHierarchy(
                target.organizationId(),
                rankCode,
                target.contractDate()
        );
        if (!agentId.equals(expectedManagerId)) {
            invalid("attributedContractId",
                    "지급 대상 설계사가 귀속계약 조직의 " + rankCode.name() + " 관리자가 아닙니다.");
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
            String inclusionReason,
            boolean policyVersionResolutionPending
    ) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("sourceContractId", sourceContractId);
        snapshot.put("allocationBasis", allocationBasis);
        snapshot.put("inclusionDecisionReason", inclusionReason);
        if (policyVersionResolutionPending) {
            snapshot.put("policyVersionResolutionPending", true);
        }
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
