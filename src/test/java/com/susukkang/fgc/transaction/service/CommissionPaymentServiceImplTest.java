package com.susukkang.fgc.transaction.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.audit.service.AuditLogService;
import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.dto.CapCalculationResult;
import com.susukkang.fgc.cap.service.CapCalculator;
import com.susukkang.fgc.cap.service.CapExceptionService;
import com.susukkang.fgc.cap.service.CapValidator;
import com.susukkang.fgc.cap.service.CapValidatorImpl;
import com.susukkang.fgc.common.code.AttributionMethod;
import com.susukkang.fgc.common.code.CapCheckKind;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.code.ExclusionType;
import com.susukkang.fgc.common.code.InclusionDecisionStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
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
import com.susukkang.fgc.transaction.dto.CommissionPaymentAttributionRequest;
import com.susukkang.fgc.transaction.dto.CommissionPaymentCreateRequest;
import com.susukkang.fgc.transaction.dto.CommissionPaymentResponse;
import com.susukkang.fgc.transaction.dto.CommissionPaymentUpdateRequest;
import com.susukkang.fgc.transaction.dto.TransactionPrecheckResponse;
import com.susukkang.fgc.transaction.mapper.CommissionPaymentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 설명 : 수수료 지급 건 등록·수정·확정 서비스 단위 테스트
 *
 * @author yslee
 * @since 2026-08-10
 * @version 1.2
 */
@ExtendWith(MockitoExtension.class)
class CommissionPaymentServiceImplTest {

    @Mock
    private CommissionPaymentMapper mapper;
    @Mock
    private CapCalculator capCalculator;
    @Mock
    private CapExceptionService capExceptionService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private CommissionPolicyService commissionPolicyService;

    private CommissionPaymentServiceImpl service;
    private FgcMessageResolver messageResolver;

    @BeforeEach
    void setUp() {
        CapValidator capValidator = new CapValidatorImpl();
        // 실제 메시지 카탈로그를 사용해 precheck blockers 문구가 confirm 오류 문구와 같은지 검증한다
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageResolver = new FgcMessageResolver(messageSource);
        service = new CommissionPaymentServiceImpl(
                mapper,
                new ObjectMapper(),
                capValidator,
                capCalculator,
                capExceptionService,
                messageResolver,
                auditLogService,
                commissionPolicyService
        );
    }

    @Test
    void resolvesAllocationPolicyWhenApprovedAllocationOmitsPolicyVersion() {
        given(mapper.existsAgent(7L)).willReturn(true);
        given(mapper.findCommissionItem(11L, LocalDate.of(2026, 7, 1)))
                .willReturn(new CommissionItemReference(11L, "BASE_COMMISSION", "PAYMENT"));
        given(mapper.findContract(3L))
                .willReturn(new ContractReference(3L, 7L, LocalDate.of(2026, 7, 3)));
        stubInsertAndResponse(List.of(attributionRow(1, 3L, "500000")));
        given(commissionPolicyService.resolveCurrentAllocationPolicyVersion(LocalDate.of(2026, 7, 3)))
                .willReturn(4L);
        given(mapper.existsPolicyVersion(4L)).willReturn(true);
        given(mapper.findAllocationPolicyId(4L, "DIRECT")).willReturn(77L);

        CommissionPaymentCreateRequest source = createRequest(List.of(
                attribution(3L, "500000", AttributionMethod.APPROVED_ALLOCATION)
        ));
        CommissionPaymentCreateRequest request = new CommissionPaymentCreateRequest(
                source.sourceType(),
                source.sourceBusinessKey(),
                source.contractId(),
                source.agentId(),
                source.commissionItemId(),
                source.amount(),
                source.settlementMonth(),
                source.cashflowType(),
                source.scheduledPaymentDate(),
                source.paymentStage(),
                null,
                source.attributions(),
                source.evidenceRef(),
                source.note()
        );

        service.create(request);

        ArgumentCaptor<CommissionPaymentCommand> captor =
                ArgumentCaptor.forClass(CommissionPaymentCommand.class);
        verify(mapper).insertTransaction(captor.capture());
        assertThat(captor.getValue().getPolicyVersionId()).isEqualTo(4L);
        verify(commissionPolicyService, never()).resolveCurrentCommission(any(Long.class), any(PaymentStage.class));
    }

    @Test
    void createsDraftWithMultipleAttributions() {
        stubReferences(3L, 9L);
        stubInsertAndResponse(List.of(attributionRow(1, 3L, "300000"), attributionRow(2, 9L, "200000")));

        CommissionPaymentCreateRequest request = createRequest(List.of(
                attribution(3L, "300000", AttributionMethod.APPROVED_ALLOCATION),
                attribution(9L, "200000", AttributionMethod.APPROVED_ALLOCATION)
        ));

        CommissionPaymentResponse response = service.create(request);

        assertThat(response.status()).isEqualTo(CommissionPaymentStatus.DRAFT);
        assertThat(response.attributions()).hasSize(2);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CommissionPaymentAttributionCommand>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper).insertAttributions(captor.capture());
        assertThat(captor.getValue())
                .extracting(CommissionPaymentAttributionCommand::getAttributionSequence)
                .containsExactly(1, 2);
        assertThat(captor.getValue())
                .extracting(CommissionPaymentAttributionCommand::getAmount)
                .containsExactly(new BigDecimal("300000"), new BigDecimal("200000"));
    }

    @Test
    void createsNonContractNewcomerSupportDraftWithoutContractOrPolicyVersion() {
        given(mapper.existsAgent(7L)).willReturn(true);
        given(mapper.findCommissionItem(11L, LocalDate.of(2026, 7, 1)))
                .willReturn(new CommissionItemReference(11L, "NEWCOMER_SUPPORT", "PAYMENT"));
        stubInsertAndResponse(List.of(new CommissionPaymentAttributionRow(
                1, null, LocalDate.of(2026, 7, 3), LocalDate.of(2026, 7, 1),
                new BigDecimal("300000"), InclusionDecisionStatus.EXCLUDED,
                ExclusionType.NEW_AGENT_SUPPORT, "신인활동지원비", "신인 지원", "EVIDENCE",
                AttributionMethod.NEWCOMER_NON_CONTRACT
        )));

        CommissionPaymentCreateRequest request = new CommissionPaymentCreateRequest(
                "GA_MANUAL_PAYMENT", "GA-2026-07-NEW-001", null, 7L, 11L,
                new BigDecimal("300000"), LocalDate.of(2026, 7, 1), "PAYMENT",
                LocalDate.of(2026, 7, 25), PaymentStage.GA_TO_FC, null,
                List.of(new CommissionPaymentAttributionRequest(
                        null, LocalDate.of(2026, 7, 3), new BigDecimal("300000"),
                        InclusionDecisionStatus.EXCLUDED, ExclusionType.NEW_AGENT_SUPPORT,
                        "신인활동지원비", "신인 지원", "EVIDENCE",
                        AttributionMethod.NEWCOMER_NON_CONTRACT
                )), "EVIDENCE", "신인 지원 지급"
        );

        service.create(request);

        ArgumentCaptor<CommissionPaymentCommand> paymentCaptor =
                ArgumentCaptor.forClass(CommissionPaymentCommand.class);
        verify(mapper).insertTransaction(paymentCaptor.capture());
        assertThat(paymentCaptor.getValue().getSourceContractId()).isNull();
        assertThat(paymentCaptor.getValue().getPolicyVersionId()).isNull();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CommissionPaymentAttributionCommand>> attributionCaptor = ArgumentCaptor.forClass(List.class);
        verify(mapper).insertAttributions(attributionCaptor.capture());
        assertThat(attributionCaptor.getValue().get(0).getContractId()).isNull();
        assertThat(attributionCaptor.getValue().get(0).getAttributionScope()).isEqualTo("AGENT");
    }

    @Test
    void savesDraftWhenAutomaticCommissionPolicyResolutionFails() {
        given(mapper.existsAgent(7L)).willReturn(true);
        given(mapper.findCommissionItem(11L, LocalDate.of(2026, 7, 1)))
                .willReturn(new CommissionItemReference(11L, "BASE_COMMISSION", "PAYMENT"));
        given(mapper.findContract(3L))
                .willReturn(new ContractReference(3L, 7L, LocalDate.of(2026, 7, 3)));
        stubInsertAndResponse(List.of(attributionRow(1, 3L, "500000")));
        given(commissionPolicyService.resolveCurrentCommission(3L, PaymentStage.GA_TO_FC))
                .willThrow(new FgcBusinessException(
                        FgcErrorCode.COMMON_002,
                        "commissionPolicy",
                        Map.of("reason", "POLICY_MISSING"),
                        "계약에 적용할 현행 수수료 정책이 없습니다."
                ));
        CommissionPaymentCreateRequest source = createRequest(List.of(
                attribution(3L, "500000", AttributionMethod.DIRECT)
        ));
        CommissionPaymentCreateRequest request = new CommissionPaymentCreateRequest(
                source.sourceType(), source.sourceBusinessKey(), source.contractId(), source.agentId(),
                source.commissionItemId(), source.amount(), source.settlementMonth(), source.cashflowType(),
                source.scheduledPaymentDate(), source.paymentStage(), null, source.attributions(),
                source.evidenceRef(), source.note()
        );

        CommissionPaymentResponse response = service.create(request);

        assertThat(response.status()).isEqualTo(CommissionPaymentStatus.DRAFT);
        ArgumentCaptor<CommissionPaymentCommand> captor =
                ArgumentCaptor.forClass(CommissionPaymentCommand.class);
        verify(mapper).insertTransaction(captor.capture());
        assertThat(captor.getValue().getPolicyVersionId()).isNull();
    }

    @Test
    void savesApprovedAllocationDraftWithPendingPolicyResolution() {
        given(mapper.existsAgent(7L)).willReturn(true);
        given(mapper.findCommissionItem(11L, LocalDate.of(2026, 7, 1)))
                .willReturn(new CommissionItemReference(11L, "BASE_COMMISSION", "PAYMENT"));
        given(mapper.findContract(3L))
                .willReturn(new ContractReference(3L, 7L, LocalDate.of(2026, 7, 3)));
        stubInsertAndResponse(List.of(attributionRow(1, 3L, "500000")));
        given(commissionPolicyService.resolveCurrentAllocationPolicyVersion(LocalDate.of(2026, 7, 3)))
                .willThrow(new FgcBusinessException(
                        FgcErrorCode.COMMON_002,
                        "allocationPolicyVersion",
                        Map.of("reason", "POLICY_MISSING"),
                        "계약에 적용할 현행 배부 정책이 없습니다."
                ));

        CommissionPaymentCreateRequest source = createRequest(List.of(
                attribution(3L, "500000", AttributionMethod.APPROVED_ALLOCATION)
        ));
        CommissionPaymentCreateRequest request = new CommissionPaymentCreateRequest(
                source.sourceType(), source.sourceBusinessKey(), source.contractId(), source.agentId(),
                source.commissionItemId(), source.amount(), source.settlementMonth(), source.cashflowType(),
                source.scheduledPaymentDate(), source.paymentStage(), null, source.attributions(),
                source.evidenceRef(), source.note()
        );

        service.create(request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CommissionPaymentAttributionCommand>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper).insertAttributions(captor.capture());
        CommissionPaymentAttributionCommand saved = captor.getValue().get(0);
        assertThat(saved.getAllocationPolicyId()).isNull();
        assertThat(saved.getAllocationBasisJson()).contains("\"policyVersionResolutionPending\":true");
    }

    @Test
    void createsInsurerToGaDraftWithoutRecipientAgent() {
        given(mapper.findCommissionItem(11L, LocalDate.of(2026, 7, 1)))
                .willReturn(new CommissionItemReference(11L, "BASE_COMMISSION", "PAYMENT"));
        given(mapper.existsPolicyVersion(3L)).willReturn(true);
        given(mapper.findContract(3L))
                .willReturn(new ContractReference(3L, 7L, LocalDate.of(2026, 7, 3)));
        stubInsertAndResponse(List.of(attributionRow(1, 3L, "500000")));
        CommissionPaymentCreateRequest source = createRequest(List.of(
                attribution(3L, "500000", AttributionMethod.DIRECT)
        ));
        CommissionPaymentCreateRequest request = new CommissionPaymentCreateRequest(
                source.sourceType(), source.sourceBusinessKey(), source.contractId(), null,
                source.commissionItemId(), source.amount(), source.settlementMonth(),
                source.cashflowType(), source.scheduledPaymentDate(), PaymentStage.INSURER_TO_GA,
                source.allocationPolicyVersion(), source.attributions(), source.evidenceRef(), source.note()
        );

        service.create(request);

        ArgumentCaptor<CommissionPaymentCommand> captor = ArgumentCaptor.forClass(CommissionPaymentCommand.class);
        verify(mapper).insertTransaction(captor.capture());
        assertThat(captor.getValue().getAgentId()).isNull();
    }

    // 2026-08-11 yslee - 귀속행 입력 전 지급 본문 DRAFT 저장 검증
    // 기존 코드: 빈 귀속 목록을 MyBatis 일괄 INSERT로 전달
    // 문제: 귀속 작업 전 임시저장이 SQL 오류로 실패
    // 개선: 지급 본문만 저장하고 빈 귀속 응답을 반환
    @Test
    void createsDraftWithoutAttributions() {
        stubReferences(3L);
        stubInsertAndResponse(List.of());

        CommissionPaymentResponse response = service.create(createRequest(List.of()));

        assertThat(response.status()).isEqualTo(CommissionPaymentStatus.DRAFT);
        assertThat(response.attributions()).isEmpty();
        verify(mapper).insertTransaction(any());
        verify(mapper, never()).insertAttributions(anyList());
    }

    // 2026-08-11 yslee - 최신 FUN-065의 저장·확정 분리 계약 검증
    // 기존 코드: 등록 성공만 확인하여 REVIEW_REQUIRED 초안 저장이 FUN-033을 호출하지 않는지 증명하지 못함
    // 문제: 저장 시 사전검증이 재도입되면 입력 중인 초안이 한도 판정 때문에 보존되지 않을 수 있음
    // 개선: 검토필요 귀속도 DRAFT로 저장하고 CapCalculator·확정용 룰 조회가 호출되지 않음을 검증
    @Test
    void savesReviewRequiredDraftWithoutFun033Validation() {
        stubReferences(3L);
        stubInsertAndResponse(List.of(new CommissionPaymentAttributionRow(
                1,
                3L,
                LocalDate.of(2026, 7, 3),
                LocalDate.of(2026, 7, 1),
                new BigDecimal("500000"),
                InclusionDecisionStatus.REVIEW_REQUIRED,
                ExclusionType.NONE,
                "담당자 검토 대기",
                "DIRECT",
                "EVIDENCE",
                AttributionMethod.DIRECT
        )));
        CommissionPaymentAttributionRequest attribution = new CommissionPaymentAttributionRequest(
                3L,
                LocalDate.of(2026, 7, 3),
                new BigDecimal("500000"),
                InclusionDecisionStatus.REVIEW_REQUIRED,
                ExclusionType.NONE,
                "담당자 검토 대기",
                "DIRECT",
                "EVIDENCE",
                AttributionMethod.DIRECT
        );

        CommissionPaymentResponse response = service.create(createRequest(List.of(attribution)));

        assertThat(response.status()).isEqualTo(CommissionPaymentStatus.DRAFT);
        verify(capCalculator, never()).calculate(any());
        verify(mapper, never()).findCapRuleSnapshot(any(), any());
        verify(mapper, never()).insertCapCheck(any());
    }

    // 2026-08-11 yslee - 지급액과 상세 귀속액을 저장 전에 각각 원 단위 HALF_UP 처리
    // 기존 코드: 소수 금액을 numeric(15,2)에 그대로 저장하여 원 단위 합산 규칙과 불일치
    // 문제: 상세행을 반올림한 합계와 지급 건 금액이 달라 확정 경계에서 오판 가능
    // 개선: 지급 건과 각 귀속행을 독립 반올림하고 실제 귀속일에서 월 집계키를 파생
    @Test
    void roundsEachAttributionToWonAndDerivesMonthFromActualDate() {
        stubReferences(3L, 9L);
        stubInsertAndResponse(List.of(
                attributionRow(1, 3L, "101"),
                attributionRow(2, 9L, "200")
        ));
        CommissionPaymentCreateRequest request = createRequest(List.of(
                attribution(3L, "100.50", AttributionMethod.APPROVED_ALLOCATION),
                attribution(9L, "200.49", AttributionMethod.APPROVED_ALLOCATION)
        ));

        service.create(request);

        ArgumentCaptor<CommissionPaymentCommand> paymentCaptor =
                ArgumentCaptor.forClass(CommissionPaymentCommand.class);
        verify(mapper).insertTransaction(paymentCaptor.capture());
        assertThat(paymentCaptor.getValue().getAmount()).isEqualByComparingTo("301");
        assertThat(paymentCaptor.getValue().getEvidenceRef()).isEqualTo("PAYMENT-EVIDENCE");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CommissionPaymentAttributionCommand>> attributionCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(mapper).insertAttributions(attributionCaptor.capture());
        assertThat(attributionCaptor.getValue())
                .extracting(CommissionPaymentAttributionCommand::getAmount)
                .containsExactly(new BigDecimal("101"), new BigDecimal("200"));
        assertThat(attributionCaptor.getValue())
                .extracting(CommissionPaymentAttributionCommand::getAttributionDate)
                .containsOnly(LocalDate.of(2026, 7, 3));
        assertThat(attributionCaptor.getValue())
                .extracting(CommissionPaymentAttributionCommand::getAttributionMonth)
                .containsOnly(LocalDate.of(2026, 7, 1));
    }

    // 2026-08-11 yslee - 행별 반올림과 합계 후 반올림이 갈리는 입력값 검증
    // 기존 코드: 100.50과 200.49는 두 계산 순서 모두 합계 301원이라 순서 회귀를 찾지 못함
    // 문제: 상세행 반올림이 합계 뒤로 이동해도 테스트가 통과
    // 개선: 100.50원 두 행을 각각 101원으로 만든 뒤 202원 지급액과 일치하는지 검증
    @Test
    void roundsEachAttributionBeforeComparingWithPaymentTotal() {
        stubReferences(3L, 9L);
        stubInsertAndResponse(List.of(
                attributionRow(1, 3L, "101"),
                attributionRow(2, 9L, "101")
        ));
        List<CommissionPaymentAttributionRequest> attributions = List.of(
                attribution(3L, "100.50", AttributionMethod.APPROVED_ALLOCATION),
                attribution(9L, "100.50", AttributionMethod.APPROVED_ALLOCATION)
        );
        CommissionPaymentCreateRequest source = createRequest(attributions);
        CommissionPaymentCreateRequest request = new CommissionPaymentCreateRequest(
                source.sourceType(),
                source.sourceBusinessKey(),
                source.contractId(),
                source.agentId(),
                source.commissionItemId(),
                new BigDecimal("202"),
                source.settlementMonth(),
                source.cashflowType(),
                source.scheduledPaymentDate(),
                source.paymentStage(),
                source.allocationPolicyVersion(),
                source.attributions(),
                source.evidenceRef(),
                source.note()
        );

        service.create(request);

        ArgumentCaptor<CommissionPaymentCommand> paymentCaptor =
                ArgumentCaptor.forClass(CommissionPaymentCommand.class);
        verify(mapper).insertTransaction(paymentCaptor.capture());
        assertThat(paymentCaptor.getValue().getAmount()).isEqualByComparingTo("202");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CommissionPaymentAttributionCommand>> attributionCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(mapper).insertAttributions(attributionCaptor.capture());
        assertThat(attributionCaptor.getValue())
                .extracting(CommissionPaymentAttributionCommand::getAmount)
                .containsExactly(new BigDecimal("101"), new BigDecimal("101"));
    }

    // 2026-08-11 yslee - 인터페이스 월 날짜 형식의 월초 제약 검증
    // 기존 코드: YearMonth로 YYYY-MM만 받아 API의 YYYY-MM-01 계약과 불일치
    // 문제: LocalDate 전환 후 월중 날짜까지 정산월로 저장하면 DB CHECK에서 뒤늦게 실패
    // 개선: 서비스 입력 경계에서 월초가 아닌 settlementMonth를 FGC-COMMON-002로 차단
    @Test
    void rejectsSettlementMonthThatIsNotFirstDay() {
        CommissionPaymentCreateRequest request = createRequest(List.of(
                attribution(3L, "500000", AttributionMethod.DIRECT)
        ));

        assertThatThrownBy(() -> service.create(withSettlementMonth(
                request, LocalDate.of(2026, 7, 2)
        ))).isInstanceOfSatisfying(FgcBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(FgcErrorCode.COMMON_002));

        verify(mapper, never()).insertTransaction(any());
    }

    // 2026-08-11 yslee - REG-20 지급월 신계약 귀속 정상 시나리오 검증
    // 기존 코드: 정착지원금 귀속월 규칙을 구현했지만 수수료 항목과 귀속방식 조합을 확인하지 않음
    // 문제: 다른 수수료가 정착지원금 방식으로 우회 귀속되거나 정착지원금이 일반 귀속으로 저장 가능
    // 개선: SETTLEMENT_SUPPORT 항목의 지급월 신계약·실제 귀속일·월초 파생을 함께 검증
    @Test
    void attributesSettlementSupportToNewContractInSettlementMonth() {
        stubReferences(3L);
        given(mapper.findCommissionItem(11L, LocalDate.of(2026, 7, 1)))
                .willReturn(new CommissionItemReference(
                        11L, "SETTLEMENT_SUPPORT", "PAYMENT"
                ));
        stubInsertAndResponse(List.of(attributionRow(1, 3L, "500000")));

        service.create(createRequest(List.of(
                attribution(3L, "500000", AttributionMethod.SETTLEMENT_SUPPORT_MONTHLY)
        )));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CommissionPaymentAttributionCommand>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(mapper).insertAttributions(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(attribution -> {
            assertThat(attribution.getAttributionDate()).isEqualTo(LocalDate.of(2026, 7, 3));
            assertThat(attribution.getAttributionMonth()).isEqualTo(LocalDate.of(2026, 7, 1));
        });
    }

    @Test
    void rejectsSettlementAttributionMethodForOtherCommissionItem() {
        given(mapper.existsAgent(7L)).willReturn(true);
        given(mapper.findCommissionItem(11L, LocalDate.of(2026, 7, 1)))
                .willReturn(new CommissionItemReference(
                        11L, "BASE_COMMISSION", "PAYMENT"
                ));

        assertThatThrownBy(() -> service.create(createRequest(List.of(
                attribution(3L, "500000", AttributionMethod.SETTLEMENT_SUPPORT_MONTHLY)
        )))).isInstanceOfSatisfying(FgcBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(FgcErrorCode.COMMON_002));

        verify(mapper, never()).insertTransaction(any());
    }

    @Test
    void rejectsCarryForwardWhenEarlierContractMonthExists() {
        stubReferences(3L);
        given(mapper.findCommissionItem(11L, LocalDate.of(2026, 7, 1)))
                .willReturn(new CommissionItemReference(
                        11L, "SETTLEMENT_SUPPORT", "PAYMENT"
                ));
        given(mapper.countContractsBeforeMonth(7L, LocalDate.of(2026, 7, 1)))
                .willReturn(1);
        given(mapper.findAgentAppointmentDate(7L))
                .willReturn(LocalDate.of(2026, 7, 15));

        assertThatThrownBy(() -> service.create(createRequest(List.of(
                attribution(3L, "500000", AttributionMethod.FIRST_CONTRACT_CARRY_FORWARD)
        )))).isInstanceOfSatisfying(FgcBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(FgcErrorCode.COMMON_002));

        verify(mapper, never()).insertTransaction(any());
    }

    // 2026-08-11 yslee - REG-20 최초 신계약 모집월 이월 정상 시나리오 검증
    // 기존 코드: 이전 계약이 있을 때의 차단만 테스트하여 실제 다음 달 이월 성공 경로가 검증되지 않음
    // 문제: 정산월·실제 귀속월 비교가 빠져도 테스트가 통과하여 같은 달을 이월로 잘못 저장할 수 있음
    // 개선: 7월 무실적 지급분을 계약이 처음 생긴 8월 실제 일자로 귀속하고 월초 파생을 확인
    @Test
    void carriesForwardSettlementSupportToFirstContractMonthAfterSettlementMonth() {
        given(mapper.existsAgent(7L)).willReturn(true);
        given(mapper.findCommissionItem(11L, LocalDate.of(2026, 7, 1)))
                .willReturn(new CommissionItemReference(
                        11L, "SETTLEMENT_SUPPORT", "PAYMENT"
                ));
        given(mapper.existsPolicyVersion(3L)).willReturn(true);
        given(mapper.findContract(3L))
                .willReturn(new ContractReference(3L, 7L, LocalDate.of(2026, 8, 5)));
        given(mapper.countContractsBeforeMonth(7L, LocalDate.of(2026, 8, 1)))
                .willReturn(0);
        given(mapper.findAgentAppointmentDate(7L))
                .willReturn(LocalDate.of(2026, 7, 15));
        stubInsertAndResponse(List.of(new CommissionPaymentAttributionRow(
                1,
                3L,
                LocalDate.of(2026, 8, 5),
                LocalDate.of(2026, 8, 1),
                new BigDecimal("500000"),
                InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE,
                "최초 신계약 모집월 이월",
                "FIRST_PREMIUM_RATIO",
                "IT-REG20",
                AttributionMethod.FIRST_CONTRACT_CARRY_FORWARD
        )));
        CommissionPaymentAttributionRequest attribution = new CommissionPaymentAttributionRequest(
                3L,
                LocalDate.of(2026, 8, 5),
                new BigDecimal("500000"),
                InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE,
                "최초 신계약 모집월 이월",
                "FIRST_PREMIUM_RATIO",
                "IT-REG20",
                AttributionMethod.FIRST_CONTRACT_CARRY_FORWARD
        );

        service.create(createRequest(List.of(attribution)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CommissionPaymentAttributionCommand>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(mapper).insertAttributions(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(saved -> {
            assertThat(saved.getAttributionDate()).isEqualTo(LocalDate.of(2026, 8, 5));
            assertThat(saved.getAttributionMonth()).isEqualTo(LocalDate.of(2026, 8, 1));
        });
    }

    @Test
    void rejectsCarryForwardWhenFirstContractIsInSettlementMonth() {
        stubReferences(3L);
        given(mapper.findCommissionItem(11L, LocalDate.of(2026, 7, 1)))
                .willReturn(new CommissionItemReference(
                        11L, "SETTLEMENT_SUPPORT", "PAYMENT"
                ));
        given(mapper.countContractsBeforeMonth(7L, LocalDate.of(2026, 7, 1)))
                .willReturn(0);
        given(mapper.findAgentAppointmentDate(7L))
                .willReturn(LocalDate.of(2026, 7, 15));

        assertThatThrownBy(() -> service.create(createRequest(List.of(
                attribution(3L, "500000", AttributionMethod.FIRST_CONTRACT_CARRY_FORWARD)
        )))).isInstanceOfSatisfying(FgcBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(FgcErrorCode.COMMON_002));

        verify(mapper, never()).insertTransaction(any());
    }

    @Test
    void rejectsCarryForwardWhenSettlementMonthIsNotAppointmentMonth() {
        given(mapper.existsAgent(7L)).willReturn(true);
        given(mapper.findCommissionItem(11L, LocalDate.of(2026, 7, 1)))
                .willReturn(new CommissionItemReference(
                        11L, "SETTLEMENT_SUPPORT", "PAYMENT"
                ));
        given(mapper.existsPolicyVersion(3L)).willReturn(true);
        given(mapper.findContract(3L))
                .willReturn(new ContractReference(3L, 7L, LocalDate.of(2026, 8, 5)));
        given(mapper.countContractsBeforeMonth(7L, LocalDate.of(2026, 8, 1)))
                .willReturn(0);
        given(mapper.findAgentAppointmentDate(7L))
                .willReturn(LocalDate.of(2026, 6, 15));
        CommissionPaymentAttributionRequest attribution = new CommissionPaymentAttributionRequest(
                3L,
                LocalDate.of(2026, 8, 5),
                new BigDecimal("500000"),
                InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE,
                "최초 신계약 모집월 이월",
                "FIRST_PREMIUM_RATIO",
                "IT-REG20",
                AttributionMethod.FIRST_CONTRACT_CARRY_FORWARD
        );

        assertThatThrownBy(() -> service.create(createRequest(List.of(attribution))))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(FgcErrorCode.COMMON_002));

        verify(mapper, never()).insertTransaction(any());
    }

    @Test
    void updateReplacesAttributionsOnlyWhileDraft() {
        stubReferences(3L, 9L);
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(List.of(confirmation(
                201L, 3L, "500000", "500000", InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE, AttributionMethod.DIRECT, "EVIDENCE"
        )));
        given(mapper.findById(101L)).willReturn(row(CommissionPaymentStatus.DRAFT));
        given(mapper.findAttributions(101L)).willReturn(List.of(attributionRow(1, 9L, "500000")));
        given(mapper.updateTransaction(any(CommissionPaymentCommand.class))).willReturn(1);

        CommissionPaymentUpdateRequest request = updateRequest(List.of(
                attribution(9L, "500000", AttributionMethod.APPROVED_ALLOCATION)
        ));

        CommissionPaymentResponse response = service.update(101L, request);

        assertThat(response.attributions()).extracting(a -> a.contractId()).containsExactly(9L);
        verify(mapper).updateTransaction(any(CommissionPaymentCommand.class));
        verify(mapper).detachPreConfirmDetails(101L);
        verify(mapper).deleteAttributions(101L);
        verify(mapper).insertAttributions(anyList());
    }

    // 2026-08-11 yslee - 기존 귀속을 모두 지운 DRAFT 수정 검증
    // 기존 코드: 수정 요청도 귀속행 1건 이상을 강제
    // 문제: 작성 중인 DRAFT에서 잘못 입력한 귀속을 전부 제거해 저장할 수 없음
    // 개선: DRAFT 본문은 갱신하고 기존 귀속 삭제 후 빈 상세 INSERT는 생략
    @Test
    void updatesDraftToEmptyAttributionList() {
        stubReferences(3L);
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(List.of(confirmation(
                201L, 3L, "500000", "500000", InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE, AttributionMethod.DIRECT, "EVIDENCE"
        )));
        given(mapper.updateTransaction(any(CommissionPaymentCommand.class))).willReturn(1);
        given(mapper.findById(101L)).willReturn(row(CommissionPaymentStatus.DRAFT));
        given(mapper.findAttributions(101L)).willReturn(List.of());

        CommissionPaymentResponse response = service.update(101L, updateRequest(List.of()));

        assertThat(response.attributions()).isEmpty();
        verify(mapper).deleteAttributions(101L);
        verify(mapper, never()).insertAttributions(anyList());
    }

    @Test
    void rejectsUpdateWhenPaymentIsNotDraft() {
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(List.of(withStatus(confirmation(
                201L, 3L, "500000", "500000", InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE, AttributionMethod.DIRECT, "EVIDENCE"
        ), CommissionPaymentStatus.CONFIRMED)));

        assertThatThrownBy(() -> service.update(101L, updateRequest(List.of(
                attribution(3L, "500000", AttributionMethod.DIRECT)
        )))).isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.TRAN_005));
    }

    // 2026-08-11 yslee - 귀속행 없는 DRAFT의 확정 차단 검증
    // 기존 코드: INNER JOIN 조회로 지급 건을 미존재 처리
    // 문제: 귀속 누락 업무 오류가 COMMON-002로 잘못 응답되고 예외 이력도 남지 않음
    // 개선: 지급 본문을 조회한 뒤 TRAN-002와 DATA_QUALITY 예외를 생성
    @Test
    void blocksConfirmationWhenDraftHasNoAttributions() {
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(List.of(
                emptyDraftConfirmation()
        ));

        assertThatThrownBy(() -> service.confirm(101L, null))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(FgcErrorCode.TRAN_002));

        ArgumentCaptor<com.susukkang.fgc.transaction.domain.ExceptionCaseCommand> captor =
                ArgumentCaptor.forClass(com.susukkang.fgc.transaction.domain.ExceptionCaseCommand.class);
        verify(mapper).insertExceptionCase(captor.capture());
        assertThat(captor.getValue().getExceptionType()).isEqualTo("DATA_QUALITY");
        verify(capCalculator, never()).calculate(any());
        verify(mapper, never()).confirm(any(), any(), any());
    }

    @Test
    void blocksNonContractNewcomerSupportWhenRecipientIsNotEligible() {
        ConfirmationData newcomer = confirmation(
                201L, null, "500000", "500000", InclusionDecisionStatus.EXCLUDED,
                ExclusionType.NEW_AGENT_SUPPORT, AttributionMethod.NEWCOMER_NON_CONTRACT, "EVIDENCE"
        );
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(List.of(newcomer));
        given(mapper.existsEligibleNewcomerSupportAgent(7L, LocalDate.of(2026, 7, 3))).willReturn(false);

        assertThatThrownBy(() -> service.confirm(101L, null))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.CAP_002));

        verify(mapper).insertExceptionCase(any());
        verify(mapper, never()).findCapRuleSnapshot(any(), any());
        verify(mapper, never()).confirm(any(), any(), any());
    }

    @Test
    void blocksConfirmationWithTransactionPolicyVersionErrorWhenPolicyIsMissing() {
        ConfirmationData withoutPolicy = withPolicyVersion(confirmation(
                201L, 3L, "500000", "500000", InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE, AttributionMethod.DIRECT, "EVIDENCE"
        ), null);
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(List.of(withoutPolicy));

        assertThatThrownBy(() -> service.confirm(101L, null))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.TRAN_007));

        verify(mapper).insertExceptionCase(any());
        verify(mapper, never()).confirm(any(), any(), any());
    }

    @Test
    void confirmsEveryAttributionAfterFun033Validation() {
        List<ConfirmationData> data = List.of(
                confirmation(201L, 3L, "300000", "500000", InclusionDecisionStatus.INCLUDED,
                        ExclusionType.NONE, AttributionMethod.APPROVED_ALLOCATION, "EVIDENCE-1"),
                confirmation(202L, 9L, "200000", "500000", InclusionDecisionStatus.INCLUDED,
                        ExclusionType.NONE, AttributionMethod.APPROVED_ALLOCATION, "EVIDENCE-2")
        );
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(data);
        given(mapper.findCapRuleSnapshot(101L, 201L)).willReturn(capRule("0", null));
        given(mapper.findCapRuleSnapshot(101L, 202L)).willReturn(capRule("0", null));
        given(capCalculator.calculate(any())).willReturn(capCalculation(3L), capCalculation(9L));
        doAnswer(invocation -> {
            invocation.<CapCheckCommand>getArgument(0).setCapCheckId(55L);
            return null;
        }).when(mapper).insertCapCheck(any());
        given(mapper.confirm(101L, null, "55,55")).willReturn(1);
        given(mapper.findById(101L)).willReturn(row(CommissionPaymentStatus.CONFIRMED));
        given(mapper.findAttributions(101L)).willReturn(List.of(
                attributionRow(1, 3L, "300000"),
                attributionRow(2, 9L, "200000")
        ));

        CommissionPaymentResponse response = service.confirm(101L, null);

        assertThat(response.status()).isEqualTo(CommissionPaymentStatus.CONFIRMED);
        verify(mapper, org.mockito.Mockito.times(2)).insertCapCheck(any());
        verify(mapper, org.mockito.Mockito.times(2)).insertCapCheckDetail(any());
        verify(mapper).lockAttributedContracts(101L);
        verify(mapper).confirm(101L, null, "55,55");
    }

    // 2026-08-11 yslee - IF-API-25 멱등키 재요청은 최초 확정 결과를 재사용
    // 기존 코드: 재전송된 확정 요청을 DRAFT 아님 오류로 처리하고 capCheckIds도 반환하지 않음
    // 문제: 클라이언트가 네트워크 실패 후 최초 요청의 성공 여부를 안전하게 복구할 수 없음
    // 개선: 동일 지급 건·멱등키의 CONFIRMED 결과와 저장된 cap_check ID를 재조회
    @Test
    void returnsOriginalConfirmationForSameIdempotencyKey() {
        ConfirmationData confirmed = withConfirmationState(
                confirmation(
                        201L, 3L, "500000", "500000", InclusionDecisionStatus.INCLUDED,
                        ExclusionType.NONE, AttributionMethod.DIRECT, "EVIDENCE"
                ),
                CommissionPaymentStatus.CONFIRMED,
                "confirm-101"
        );
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(List.of(confirmed));
        given(mapper.findCapCheckIds(101L)).willReturn(List.of(55L));
        given(mapper.findById(101L)).willReturn(row(CommissionPaymentStatus.CONFIRMED));
        given(mapper.findAttributions(101L)).willReturn(List.of(attributionRow(1, 3L, "500000")));

        CommissionPaymentResponse response = service.confirm(101L, "confirm-101");

        assertThat(response.capCheckIds()).containsExactly(55L);
        verify(capCalculator, never()).calculate(any());
        verify(mapper, never()).confirm(any(), any(), any());
    }

    // 2026-08-12 hjKang - FGC-FUN-031 합산액과 FGC-FUN-033 한도 판정의 1원 미만 경계 검증
    // 기존 코드: 한도와 정확히 같은 금액 및 1원 초과 금액만 검증하여 한도 직전 정상 확정을 증명하지 못함
    // 문제: 경계 비교식이 잘못 변경되면 1,199,999원도 CAP_001로 차단될 수 있음
    // 개선: 1,200,000원 한도보다 1원 적은 산입액은 지급 확정되는지 검증
    @Test
    void confirmsOneWonBelowTwelveHundredPercentBoundary() {
        ConfirmationData data = confirmation(
                201L, 3L, "1199999", "1199999", InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE, AttributionMethod.DIRECT, "EVIDENCE"
        );
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(List.of(data));
        given(mapper.findCapRuleSnapshot(101L, 201L)).willReturn(capRule("0", null));
        given(capCalculator.calculate(any())).willReturn(capCalculation(3L));
        doAnswer(invocation -> {
            invocation.<CapCheckCommand>getArgument(0).setCapCheckId(60L);
            return null;
        }).when(mapper).insertCapCheck(any());
        given(mapper.confirm(101L, "boundary-below", "60")).willReturn(1);
        given(mapper.findById(101L)).willReturn(row(CommissionPaymentStatus.CONFIRMED));
        given(mapper.findAttributions(101L)).willReturn(List.of(attributionRow(1, 3L, "1199999")));

        CommissionPaymentResponse response = service.confirm(101L, "boundary-below");

        ArgumentCaptor<CapCheckCommand> captor = ArgumentCaptor.forClass(CapCheckCommand.class);
        verify(mapper).insertCapCheck(captor.capture());
        assertThat(captor.getValue().getIncludedAmount()).isEqualByComparingTo("1199999");
        assertThat(response.status()).isEqualTo(CommissionPaymentStatus.CONFIRMED);
        assertThat(response.capCheckIds()).containsExactly(60L);
        verify(mapper).confirm(101L, "boundary-below", "60");
    }

    @Test
    void confirmsAtExactTwelveHundredPercentBoundary() {
        ConfirmationData data = confirmation(
                201L, 3L, "1200000", "1200000", InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE, AttributionMethod.DIRECT, "EVIDENCE"
        );
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(List.of(data));
        given(mapper.findCapRuleSnapshot(101L, 201L)).willReturn(capRule("0", null));
        given(capCalculator.calculate(any())).willReturn(capCalculation(3L));
        doAnswer(invocation -> {
            invocation.<CapCheckCommand>getArgument(0).setCapCheckId(61L);
            return null;
        }).when(mapper).insertCapCheck(any());
        given(mapper.confirm(101L, "boundary-ok", "61")).willReturn(1);
        given(mapper.findById(101L)).willReturn(row(CommissionPaymentStatus.CONFIRMED));
        given(mapper.findAttributions(101L)).willReturn(List.of(attributionRow(1, 3L, "1200000")));

        CommissionPaymentResponse response = service.confirm(101L, "boundary-ok");

        assertThat(response.status()).isEqualTo(CommissionPaymentStatus.CONFIRMED);
        assertThat(response.capCheckIds()).containsExactly(61L);
    }

    @Test
    void blocksOneWonOverTwelveHundredPercentBoundary() {
        ConfirmationData data = confirmation(
                201L, 3L, "1200001", "1200001", InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE, AttributionMethod.DIRECT, "EVIDENCE"
        );
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(List.of(data));
        given(mapper.findCapRuleSnapshot(101L, 201L)).willReturn(capRule("0", null));
        given(capCalculator.calculate(any())).willReturn(capCalculation(3L));
        doAnswer(invocation -> {
            invocation.<CapCheckCommand>getArgument(0).setCapCheckId(62L);
            return null;
        }).when(mapper).insertCapCheck(any());

        assertThatThrownBy(() -> service.confirm(101L, "boundary-block"))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.CAP_001));

        verify(mapper, never()).confirm(any(), any(), any());
    }

    // 2026-08-11 yslee - WARNING 경고율 직전 89% 경계 검증
    // 기존 코드: 90% 이상 사례만 있어 비교 연산이 >로 바뀌거나 임계값이 낮아지는 회귀를 탐지하지 못함
    // 문제: 정상 건이 경고 예외로 잘못 분류될 수 있음
    // 개선: 한도 1,200,000원의 정확한 89%는 NORMAL임을 검증
    @Test
    void keepsNormalAtEightyNinePercentUsage() {
        ConfirmationData data = confirmation(
                201L, 3L, "1068000", "1068000", InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE, AttributionMethod.DIRECT, "EVIDENCE"
        );
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(List.of(data));
        given(mapper.findCapRuleSnapshot(101L, 201L)).willReturn(capRule("0", null));
        given(capCalculator.calculate(any())).willReturn(capCalculation(3L));
        doAnswer(invocation -> {
            invocation.<CapCheckCommand>getArgument(0).setCapCheckId(63L);
            return null;
        }).when(mapper).insertCapCheck(any());
        given(mapper.confirm(101L, "warning-89", "63")).willReturn(1);
        given(mapper.findById(101L)).willReturn(row(CommissionPaymentStatus.CONFIRMED));
        given(mapper.findAttributions(101L)).willReturn(List.of(attributionRow(1, 3L, "1068000")));

        service.confirm(101L, "warning-89");

        ArgumentCaptor<CapCheckCommand> captor = ArgumentCaptor.forClass(CapCheckCommand.class);
        verify(mapper).insertCapCheck(captor.capture());
        assertThat(captor.getValue().getUsagePct()).isEqualByComparingTo("89");
        assertThat(captor.getValue().getResultStatus()).isEqualTo(CapResultStatus.NORMAL);
        verify(mapper, never()).insertExceptionCase(any());
    }

    // 2026-08-11 yslee - WARNING 경고율 정확히 90% 경계 검증
    // 기존 코드: 경고 범위 중간값만 검증하여 경계 포함 조건을 직접 증명하지 못함
    // 문제: 비교 연산이 >=에서 >로 바뀌면 정확히 90%인 지급 건이 NORMAL로 통과
    // 개선: 한도 1,200,000원의 정확한 90%는 WARNING이며 경고 예외를 저장
    @Test
    void flagsWarningAtExactlyNinetyPercentUsage() {
        ConfirmationData data = confirmation(
                201L, 3L, "1080000", "1080000", InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE, AttributionMethod.DIRECT, "EVIDENCE"
        );
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(List.of(data));
        given(mapper.findCapRuleSnapshot(101L, 201L)).willReturn(capRule("0", null));
        given(capCalculator.calculate(any())).willReturn(capCalculation(3L));
        doAnswer(invocation -> {
            invocation.<CapCheckCommand>getArgument(0).setCapCheckId(64L);
            return null;
        }).when(mapper).insertCapCheck(any());
        given(mapper.confirm(101L, "warning-90", "64")).willReturn(1);
        given(mapper.findById(101L)).willReturn(row(CommissionPaymentStatus.CONFIRMED));
        given(mapper.findAttributions(101L)).willReturn(List.of(attributionRow(1, 3L, "1080000")));

        service.confirm(101L, "warning-90");

        ArgumentCaptor<CapCheckCommand> captor = ArgumentCaptor.forClass(CapCheckCommand.class);
        verify(mapper).insertCapCheck(captor.capture());
        assertThat(captor.getValue().getUsagePct()).isEqualByComparingTo("90");
        assertThat(captor.getValue().getResultStatus()).isEqualTo(CapResultStatus.WARNING);
        verify(capExceptionService).createIfNecessary(any());
    }

    // 2026-08-11 yslee - 지급 정책과 계산 정책 불일치의 업무 예외 변환 검증
    // 기존 코드: CAP_RULE_MISMATCH 저장 시 DB CHECK 위반으로 500 오류 발생
    // 문제: 의도한 FGC-CAP-002 확정 차단과 예외 이력이 보존되지 않음
    // 개선: 불일치 유형을 저장하고 검토필요 업무 오류로 확정을 차단
    @Test
    void blocksConfirmationWithCapRuleMismatch() {
        ConfirmationData data = confirmation(
                201L, 3L, "500000", "500000", InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE, AttributionMethod.DIRECT, "EVIDENCE"
        );
        CapRuleSnapshot mismatchedRule = new CapRuleSnapshot(
                99L,
                41L,
                InclusionDecisionStatus.INCLUDED,
                "지급 정책 룰셋",
                new BigDecimal("100000"),
                new BigDecimal("12"),
                new BigDecimal("90"),
                BigDecimal.ZERO,
                null
        );
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(List.of(data));
        given(mapper.findCapRuleSnapshot(101L, 201L)).willReturn(mismatchedRule);
        given(capCalculator.calculate(any())).willReturn(capCalculation(3L));

        assertThatThrownBy(() -> service.confirm(101L, null))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(FgcErrorCode.CAP_002));

        ArgumentCaptor<com.susukkang.fgc.transaction.domain.ExceptionCaseCommand> captor =
                ArgumentCaptor.forClass(com.susukkang.fgc.transaction.domain.ExceptionCaseCommand.class);
        verify(mapper).insertExceptionCase(captor.capture());
        assertThat(captor.getValue().getExceptionType()).isEqualTo("CAP_RULE_MISMATCH");
        verify(mapper, never()).insertCapCheck(any());
        verify(mapper, never()).confirm(any(), any(), any());
    }

    @Test
    void blocksReviewRequiredAttributionBeforeCapCalculation() {
        ConfirmationData data = confirmation(
                201L, 3L, "500000", "500000", InclusionDecisionStatus.REVIEW_REQUIRED,
                ExclusionType.NONE, AttributionMethod.MANUAL_REVIEW, "EVIDENCE"
        );
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(List.of(data));

        assertThatThrownBy(() -> service.confirm(101L, null))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.CAP_002));

        verify(capCalculator, never()).calculate(any());
        verify(mapper).insertExceptionCase(any());
        verify(mapper, never()).confirm(any(), any(), any());
    }

    // 2026-08-11 yslee - 정착지원금 배부근거 누락의 확정 차단 검증
    // 기존 코드: 승인 배부만 검사하여 월 배부·이월 배부의 allocationBasis가 없어도 확정 가능
    // 문제: REG-20의 계약별 배부 기준을 사후에 재현할 수 없는 지급 건이 생성됨
    // 개선: 배부근거 없는 정착지원금은 예외를 저장하고 FUN-033 계산 전에 FGC-TRAN-004로 차단
    @Test
    void blocksSettlementSupportConfirmationWithoutAllocationBasis() {
        ConfirmationData data = withAllocationBasis(confirmation(
                201L, 3L, "500000", "500000", InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE, AttributionMethod.SETTLEMENT_SUPPORT_MONTHLY, "EVIDENCE"
        ), null);
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(List.of(data));

        assertThatThrownBy(() -> service.confirm(101L, null))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(FgcErrorCode.TRAN_004));

        verify(mapper).insertExceptionCase(any());
        verify(capCalculator, never()).calculate(any());
        verify(mapper, never()).confirm(any(), any(), any());
    }

    @Test
    void blocksConfirmationAndCreatesExceptionWhenCapIsExceeded() {
        ConfirmationData data = confirmation(
                201L, 3L, "1300000", "1300000", InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE, AttributionMethod.DIRECT, "EVIDENCE"
        );
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(List.of(data));
        given(mapper.findCapRuleSnapshot(101L, 201L)).willReturn(capRule("0", null));
        given(capCalculator.calculate(any())).willReturn(capCalculation(3L));
        doAnswer(invocation -> {
            invocation.<CapCheckCommand>getArgument(0).setCapCheckId(56L);
            return null;
        }).when(mapper).insertCapCheck(any());

        assertThatThrownBy(() -> service.confirm(101L, null))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.CAP_001));

        verify(capExceptionService).createIfNecessary(any());
        verify(mapper, never()).confirm(any(), any(), any());
    }

    @Test
    void blocksConfirmationWhileCapViolationExceptionIsUnresolved() {
        ConfirmationData data = confirmation(
                201L, 3L, "500000", "500000", InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE, AttributionMethod.DIRECT, "EVIDENCE"
        );
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(List.of(data));
        given(capExceptionService.hasUnresolvedViolation(101L)).willReturn(true);

        assertThatThrownBy(() -> service.confirm(101L, null))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.CAP_003));

        verify(capCalculator, never()).calculate(any());
        verify(mapper, never()).confirm(any(), any(), any());
    }

    // 2026-08-10 yslee - 지급기준 스케줄이 이미 한도를 넘은 후보 지급 확정 차단
    // 기존 코드: CapCalculator가 VIOLATION을 반환해도 limitAmount만 사용하고 스케줄 산입액은 폐기
    // 문제: CONFIRMED 수기 지급이 없으면 스케줄 사용률 119%인 계약도 후보액만으로 NORMAL 판정
    // 개선: 스케줄 판정과 실제 지급 판정 중 엄격한 결과를 cap_check에 저장하고 FGC-CAP-001로 차단
    @Test
    void blocksConfirmationWhenScheduleCalculationAlreadyViolatesLimit() {
        ConfirmationData data = confirmation(
                201L, 3L, "10000", "10000", InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE, AttributionMethod.DIRECT, "EVIDENCE"
        );
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(List.of(data));
        given(mapper.findCapRuleSnapshot(101L, 201L)).willReturn(capRule("0", null));
        given(capCalculator.calculate(any())).willReturn(capCalculation(
                3L,
                CapResultStatus.VIOLATION,
                Map.of(),
                new BigDecimal("1428000")
        ));
        doAnswer(invocation -> {
            invocation.<CapCheckCommand>getArgument(0).setCapCheckId(59L);
            return null;
        }).when(mapper).insertCapCheck(any());

        assertThatThrownBy(() -> service.confirm(101L, null))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.CAP_001));

        ArgumentCaptor<CapCheckCommand> captor = ArgumentCaptor.forClass(CapCheckCommand.class);
        verify(mapper).insertCapCheck(captor.capture());
        assertThat(captor.getValue().getIncludedAmount()).isEqualByComparingTo("1428000");
        assertThat(captor.getValue().getUsagePct()).isEqualByComparingTo("119");
        assertThat(captor.getValue().getResultStatus()).isEqualTo(CapResultStatus.VIOLATION);
        verify(capExceptionService).createIfNecessary(any());
        verify(mapper, never()).confirm(any(), any(), any());
    }

    // 2026-08-10 yslee - 스케줄과 실제 지급을 같은 금액 흐름의 병렬 관점으로 결합
    // 기존 코드: 두 값을 함께 사용하지 않아 스케줄 판정이 누락됨
    // 문제: 단순 합산으로 보완하면 대사 전 동일 지급분을 두 번 산입하여 정상 지급도 차단할 수 있음
    // 개선: 스케줄 산입액과 기존 확정액+후보액 중 큰 값을 최종 산입액으로 사용
    @Test
    void usesStricterAmountWithoutDoubleCountingScheduleAndActualViews() {
        ConfirmationData data = confirmation(
                201L, 3L, "50000", "50000", InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE, AttributionMethod.DIRECT, "EVIDENCE"
        );
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(List.of(data));
        given(mapper.findCapRuleSnapshot(101L, 201L)).willReturn(capRule("850000", null));
        given(capCalculator.calculate(any())).willReturn(capCalculation(
                3L,
                CapResultStatus.NORMAL,
                Map.of(),
                new BigDecimal("900000")
        ));
        doAnswer(invocation -> {
            invocation.<CapCheckCommand>getArgument(0).setCapCheckId(60L);
            return null;
        }).when(mapper).insertCapCheck(any());
        given(mapper.confirm(101L, null, "60")).willReturn(1);
        given(mapper.findById(101L)).willReturn(row(CommissionPaymentStatus.CONFIRMED));
        given(mapper.findAttributions(101L)).willReturn(List.of(attributionRow(1, 3L, "50000")));

        service.confirm(101L, null);

        ArgumentCaptor<CapCheckCommand> captor = ArgumentCaptor.forClass(CapCheckCommand.class);
        verify(mapper).insertCapCheck(captor.capture());
        assertThat(captor.getValue().getIncludedAmount()).isEqualByComparingTo("900000");
        assertThat(captor.getValue().getResultStatus()).isEqualTo(CapResultStatus.NORMAL);
        assertThat(captor.getValue().getCalculationSnapshotJson())
                .contains("scheduledIncludedAmount", "existingIncludedAmount", "effectiveIncludedAmount");
        verify(mapper).confirm(101L, null, "60");
    }

    @Test
    void passesVerifiedComplianceAmountToCalculator() {
        ConfirmationData data = confirmation(
                201L, 3L, "500000", "500000", InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE, AttributionMethod.DIRECT, "EVIDENCE"
        );
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(List.of(data));
        given(mapper.findCapRuleSnapshot(101L, 201L))
                .willReturn(capRule("0", new BigDecimal("2500")));
        given(capCalculator.calculate(any())).willReturn(capCalculation(3L));
        doAnswer(invocation -> {
            invocation.<CapCheckCommand>getArgument(0).setCapCheckId(57L);
            return null;
        }).when(mapper).insertCapCheck(any());
        given(mapper.confirm(101L, null, "57")).willReturn(1);
        given(mapper.findById(101L)).willReturn(row(CommissionPaymentStatus.CONFIRMED));
        given(mapper.findAttributions(101L)).willReturn(List.of(attributionRow(1, 3L, "500000")));

        service.confirm(101L, null);

        ArgumentCaptor<CapCalculationCommand> captor = ArgumentCaptor.forClass(CapCalculationCommand.class);
        verify(capCalculator).calculate(captor.capture());
        assertThat(captor.getValue().complianceEvidenceAmount()).isEqualByComparingTo("2500");
        assertThat(captor.getValue().asOfDate()).isEqualTo(LocalDate.of(2026, 7, 3));
    }

    // 2026-08-10 yslee - 준법경영비 증빙 누락의 계산 근거를 확정 차단 이력으로 보존
    // 기존 코드: 계산기가 REVIEW_REQUIRED를 반환하면 cap_check 저장 전에 예외 발생
    // 문제: 증빙 누락 당시의 최대 허용액과 실제 적용액을 감사에서 재현할 수 없음
    // 개선: REVIEW_REQUIRED 점검 및 상세를 저장한 뒤 FGC-CAP-002로 확정을 차단
    @Test
    void persistsReviewRequiredSnapshotBeforeBlockingMissingComplianceEvidence() {
        ConfirmationData data = confirmation(
                201L, 3L, "500000", "500000", InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE, AttributionMethod.DIRECT, "EVIDENCE"
        );
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(List.of(data));
        given(mapper.findCapRuleSnapshot(101L, 201L)).willReturn(capRule("0", null));
        given(capCalculator.calculate(any())).willReturn(capCalculation(
                3L,
                CapResultStatus.REVIEW_REQUIRED,
                Map.of(
                        "complianceMaximumAmount", new BigDecimal("3000"),
                        "complianceAppliedAmount", BigDecimal.ZERO
                )
        ));
        doAnswer(invocation -> {
            invocation.<CapCheckCommand>getArgument(0).setCapCheckId(58L);
            return null;
        }).when(mapper).insertCapCheck(any());

        assertThatThrownBy(() -> service.confirm(101L, null))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.CAP_002));

        ArgumentCaptor<CapCheckCommand> captor = ArgumentCaptor.forClass(CapCheckCommand.class);
        verify(mapper).insertCapCheck(captor.capture());
        assertThat(captor.getValue().getResultStatus()).isEqualTo(CapResultStatus.REVIEW_REQUIRED);
        assertThat(captor.getValue().getCalculationSnapshotJson())
                .contains("complianceMaximumAmount", "3000", "complianceAppliedAmount");
        verify(mapper).insertCapCheckDetail(any());
        verify(mapper).insertExceptionCase(any());
        verify(mapper, never()).confirm(any(), any(), any());
    }

    @Test
    void rejectsExcludedAttributionWithoutRegulatoryTypeOrEvidence() {
        stubReferences(3L);
        CommissionPaymentAttributionRequest invalid = new CommissionPaymentAttributionRequest(
                3L,
                LocalDate.of(2026, 7, 3),
                new BigDecimal("500000"),
                InclusionDecisionStatus.EXCLUDED,
                ExclusionType.NONE,
                "제외",
                "DIRECT",
                null,
                AttributionMethod.DIRECT
        );

        assertThatThrownBy(() -> service.create(createRequest(List.of(invalid))))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.TRAN_004));
    }

    // 2026-08-11 yslee - 지급 건 본문과 귀속행의 제외 증빙을 별도 계약으로 검증
    // 기존 코드: 귀속행 evidenceRef만 있으면 commission_transaction.evidence_ref 없이도 DRAFT 저장
    // 문제: 지급 원천 근거와 귀속 판정 근거를 분리 보존하는 TRAN-W02·V1 스키마 계약이 누락
    // 개선: 제외 귀속행 자체에 증빙이 있어도 지급 건 evidenceRef가 없으면 TRAN-004로 차단
    @Test
    void rejectsExcludedAttributionWithoutPaymentEvidence() {
        given(mapper.existsAgent(7L)).willReturn(true);
        given(mapper.findCommissionItem(11L, LocalDate.of(2026, 7, 1)))
                .willReturn(new CommissionItemReference(11L, "BASE_COMMISSION", "PAYMENT"));
        CommissionPaymentAttributionRequest excluded = new CommissionPaymentAttributionRequest(
                3L,
                LocalDate.of(2026, 7, 3),
                new BigDecimal("500000"),
                InclusionDecisionStatus.EXCLUDED,
                ExclusionType.VOICE_RECORDING,
                "녹취비 제외",
                "DIRECT",
                "ATTRIBUTION-EVIDENCE",
                AttributionMethod.DIRECT
        );
        CommissionPaymentCreateRequest source = createRequest(List.of(excluded));
        CommissionPaymentCreateRequest request = new CommissionPaymentCreateRequest(
                source.sourceType(),
                source.sourceBusinessKey(),
                source.contractId(),
                source.agentId(),
                source.commissionItemId(),
                source.amount(),
                source.settlementMonth(),
                source.cashflowType(),
                source.scheduledPaymentDate(),
                source.paymentStage(),
                source.allocationPolicyVersion(),
                source.attributions(),
                null,
                source.note()
        );

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.TRAN_004));

        verify(mapper, never()).insertTransaction(any());
    }

    @Test
    void rejectsComplianceExclusionForGaToFcStage() {
        stubReferences(3L);
        CommissionPaymentAttributionRequest invalid = new CommissionPaymentAttributionRequest(
                3L,
                LocalDate.of(2026, 7, 3),
                new BigDecimal("3000"),
                InclusionDecisionStatus.EXCLUDED,
                ExclusionType.COMPLIANCE_3PCT,
                "준법경영비",
                "DIRECT",
                "EVIDENCE",
                AttributionMethod.DIRECT
        );

        assertThatThrownBy(() -> service.create(createRequest(List.of(invalid))))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.COMMON_002));
    }

    @Test
    void confirmMethodDefinesTransactionBoundary() throws NoSuchMethodException {
        Transactional transactional = CommissionPaymentServiceImpl.class
                .getMethod("confirm", Long.class, String.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.noRollbackFor())
                .containsExactly(CommissionPaymentConfirmationRejectedException.class);
    }

    // FUN-061·운영정책서 제51조 — 지급 건 등록·수정·확정은 같은 트랜잭션에서 감사행을 남긴다.
    // 감사행 저장 자체(clamp·직렬화·실패 전파)는 AuditLogServiceTest, DB 왕복은
    // AuditLogQueryMapperIntegrationTest 가 검증하므로 여기서는 호출 계약만 본다.
    @Test
    void recordsPaymentCreatedAuditWithAttributionSnapshot() {
        stubReferences(3L);
        stubInsertAndResponse(List.of(attributionRow(1, 3L, "500000")));

        service.create(createRequest(List.of(
                attribution(3L, "500000", AttributionMethod.APPROVED_ALLOCATION))));

        ArgumentCaptor<AuditLogService.AuditEvent> captor =
                ArgumentCaptor.forClass(AuditLogService.AuditEvent.class);
        verify(auditLogService).record(captor.capture());
        AuditLogService.AuditEvent event = captor.getValue();
        assertThat(event.actionCode()).isEqualTo("PAYMENT_CREATED");
        assertThat(event.entityType()).isEqualTo("COMMISSION_PAYMENT");
        assertThat(event.entityId()).isEqualTo("101");
        assertThat(event.policyVersionId()).isEqualTo(3L);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> after = (java.util.Map<String, Object>) event.after();
        assertThat(after).containsKeys("payment", "attributions");
    }

    @Test
    void recordsPaymentUpdatedAuditWithBeforeSnapshot() {
        stubReferences(3L, 9L);
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(List.of(confirmation(
                201L, 3L, "500000", "500000", InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE, AttributionMethod.DIRECT, "EVIDENCE"
        )));
        given(mapper.findById(101L)).willReturn(row(CommissionPaymentStatus.DRAFT));
        given(mapper.findAttributions(101L)).willReturn(List.of(attributionRow(1, 9L, "500000")));
        given(mapper.updateTransaction(any(CommissionPaymentCommand.class))).willReturn(1);

        service.update(101L, updateRequest(List.of(
                attribution(9L, "500000", AttributionMethod.APPROVED_ALLOCATION))));

        ArgumentCaptor<AuditLogService.AuditEvent> captor =
                ArgumentCaptor.forClass(AuditLogService.AuditEvent.class);
        verify(auditLogService).record(captor.capture());
        AuditLogService.AuditEvent event = captor.getValue();
        assertThat(event.actionCode()).isEqualTo("PAYMENT_UPDATED");
        assertThat(event.entityId()).isEqualTo("101");
        // before/after 모두 같은 조회 DTO(Row) 기반 payment/attributions 구조 —
        // 스키마가 일치해야 diff 화면이 필드 단위로 비교된다
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> before = (java.util.Map<String, Object>) event.before();
        assertThat(before).containsKeys("payment", "attributions");
        assertThat(((CommissionPaymentRow) before.get("payment")).status())
                .isEqualTo(CommissionPaymentStatus.DRAFT);
        assertThat((List<?>) before.get("attributions")).hasSize(1);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> after = (java.util.Map<String, Object>) event.after();
        assertThat(after).containsKeys("payment", "attributions");
        assertThat(after.get("payment")).isInstanceOf(CommissionPaymentRow.class);
        assertThat(before.get("payment").getClass()).isEqualTo(after.get("payment").getClass());
    }

    /** 확정 감사행의 after 에는 1,200% 판정 요약(capChecks)이 담긴다 — REG-22·포함/제외 판단 근거. */
    @Test
    void recordsPaymentConfirmedAuditWithCapCheckSummaries() {
        List<ConfirmationData> data = List.of(
                confirmation(201L, 3L, "300000", "500000", InclusionDecisionStatus.INCLUDED,
                        ExclusionType.NONE, AttributionMethod.APPROVED_ALLOCATION, "EVIDENCE-1"),
                confirmation(202L, 9L, "200000", "500000", InclusionDecisionStatus.INCLUDED,
                        ExclusionType.NONE, AttributionMethod.APPROVED_ALLOCATION, "EVIDENCE-2")
        );
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(data);
        given(mapper.findCapRuleSnapshot(101L, 201L)).willReturn(capRule("0", null));
        given(mapper.findCapRuleSnapshot(101L, 202L)).willReturn(capRule("0", null));
        given(capCalculator.calculate(any())).willReturn(capCalculation(3L), capCalculation(9L));
        doAnswer(invocation -> {
            invocation.<CapCheckCommand>getArgument(0).setCapCheckId(55L);
            return null;
        }).when(mapper).insertCapCheck(any());
        given(mapper.confirm(101L, null, "55,55")).willReturn(1);
        given(mapper.findById(101L)).willReturn(row(CommissionPaymentStatus.CONFIRMED));
        given(mapper.findAttributions(101L)).willReturn(List.of(
                attributionRow(1, 3L, "300000"),
                attributionRow(2, 9L, "200000")
        ));

        service.confirm(101L, null);

        ArgumentCaptor<AuditLogService.AuditEvent> captor =
                ArgumentCaptor.forClass(AuditLogService.AuditEvent.class);
        verify(auditLogService).record(captor.capture());
        AuditLogService.AuditEvent event = captor.getValue();
        assertThat(event.actionCode()).isEqualTo("PAYMENT_CONFIRMED");
        assertThat(event.entityId()).isEqualTo("101");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> after = (java.util.Map<String, Object>) event.after();
        assertThat(after.get("status")).isEqualTo(CommissionPaymentStatus.CONFIRMED);
        assertThat((List<?>) after.get("capChecks")).hasSize(2);
    }

    /** 멱등키 재요청은 상태 변경이 아니므로 감사행을 다시 남기지 않는다. */
    @Test
    void doesNotRecordAuditOnIdempotentReconfirm() {
        ConfirmationData confirmed = withConfirmationState(
                confirmation(
                        201L, 3L, "500000", "500000", InclusionDecisionStatus.INCLUDED,
                        ExclusionType.NONE, AttributionMethod.DIRECT, "EVIDENCE"
                ),
                CommissionPaymentStatus.CONFIRMED,
                "confirm-101"
        );
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(List.of(confirmed));
        given(mapper.findCapCheckIds(101L)).willReturn(List.of(55L));
        given(mapper.findById(101L)).willReturn(row(CommissionPaymentStatus.CONFIRMED));
        given(mapper.findAttributions(101L)).willReturn(List.of(attributionRow(1, 3L, "500000")));

        service.confirm(101L, "confirm-101");

        verify(auditLogService, never()).record(any());
    }

    private void stubReferences(Long... contractIds) {
        given(mapper.existsAgent(7L)).willReturn(true);
        given(mapper.findCommissionItem(11L, LocalDate.of(2026, 7, 1)))
                .willReturn(new CommissionItemReference(
                        11L, "BASE_COMMISSION", "PAYMENT"
                ));
        given(mapper.existsPolicyVersion(3L)).willReturn(true);
        lenient().when(mapper.findAllocationPolicyId(3L, "DIRECT")).thenReturn(77L);
        for (Long contractId : contractIds) {
            lenient().when(mapper.findContract(contractId))
                    .thenReturn(new ContractReference(contractId, 7L, LocalDate.of(2026, 7, 3)));
        }
    }

    private void stubInsertAndResponse(List<CommissionPaymentAttributionRow> attributions) {
        doAnswer(invocation -> {
            invocation.<CommissionPaymentCommand>getArgument(0).setPaymentId(101L);
            return null;
        }).when(mapper).insertTransaction(any());
        given(mapper.findById(101L)).willReturn(row(CommissionPaymentStatus.DRAFT));
        given(mapper.findAttributions(101L)).willReturn(attributions);
    }

    private CommissionPaymentCreateRequest createRequest(
            List<CommissionPaymentAttributionRequest> attributions
    ) {
        return new CommissionPaymentCreateRequest(
                "GA_MANUAL_PAYMENT",
                "GA-2026-07-0001",
                3L,
                7L,
                11L,
                attributions.stream()
                        .map(CommissionPaymentAttributionRequest::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                LocalDate.of(2026, 7, 1),
                "PAYMENT",
                LocalDate.of(2026, 7, 25),
                PaymentStage.GA_TO_FC,
                3L,
                attributions,
                "PAYMENT-EVIDENCE",
                "수기 등록"
        );
    }

    private CommissionPaymentUpdateRequest updateRequest(
            List<CommissionPaymentAttributionRequest> attributions
    ) {
        CommissionPaymentCreateRequest request = createRequest(attributions);
        return new CommissionPaymentUpdateRequest(
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

    private CommissionPaymentCreateRequest withSettlementMonth(
            CommissionPaymentCreateRequest source,
            LocalDate settlementMonth
    ) {
        return new CommissionPaymentCreateRequest(
                source.sourceType(),
                source.sourceBusinessKey(),
                source.contractId(),
                source.agentId(),
                source.commissionItemId(),
                source.amount(),
                settlementMonth,
                source.cashflowType(),
                source.scheduledPaymentDate(),
                source.paymentStage(),
                source.allocationPolicyVersion(),
                source.attributions(),
                source.evidenceRef(),
                source.note()
        );
    }

    private CommissionPaymentAttributionRequest attribution(
            Long contractId,
            String amount,
            AttributionMethod method
    ) {
        return new CommissionPaymentAttributionRequest(
                contractId,
                LocalDate.of(2026, 7, 3),
                new BigDecimal(amount),
                InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE,
                "룰셋 산입",
                "DIRECT",
                "EVIDENCE",
                method
        );
    }

    /*
     * IF-API-24 (FGC-FUN-033) — 지급 전 한도 사전검증 미리보기.
     * precheck는 confirm과 같은 판정 메서드를 재사용하되 아무것도 저장하지 않는다.
     */

    @Test
    void precheckPassesCleanDraftWithoutSaving() {
        ConfirmationData data = confirmation(
                201L, 3L, "500000", "500000", InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE, AttributionMethod.DIRECT, "EVIDENCE"
        );
        given(mapper.findConfirmationData(101L)).willReturn(List.of(data));
        given(mapper.findAttributedContractNumbers(101L))
                .willReturn(List.of(new AttributedContractNo(3L, "CT-2026-0003")));
        given(mapper.findCapRuleSnapshot(101L, 201L)).willReturn(capRule("0", null));
        given(capCalculator.calculate(any())).willReturn(capCalculation(3L));

        TransactionPrecheckResponse response = service.precheck(101L);

        assertThat(response.confirmable()).isTrue();
        assertThat(response.blockers()).isEmpty();
        assertThat(response.capPreview()).hasSize(1);
        TransactionPrecheckResponse.CapPreviewItem item = response.capPreview().get(0);
        assertThat(item.contractNo()).isEqualTo("CT-2026-0003");
        assertThat(item.paymentStage()).isEqualTo(PaymentStage.GA_TO_FC);
        assertThat(item.limitAmount()).isEqualTo(1200000L);
        assertThat(item.existingIncludedAmount()).isZero();
        assertThat(item.candidateAmount()).isEqualTo(500000L);
        assertThat(item.includedAmount()).isEqualTo(500000L);
        assertThat(item.remainingAmount()).isEqualTo(700000L);
        assertThat(item.usagePct()).isEqualTo("41.666667");
        assertThat(item.resultStatus()).isEqualTo(CapResultStatus.NORMAL);
        assertThat(item.capCheckId()).isNull();
        assertPrecheckSavesNothing();
    }

    // REG-08 — 한도 초과 건은 blockers에 FGC-CAP-001과 사용률이 포함되고, 그래도 아무것도 저장하지 않는다
    @Test
    void precheckReportsCapExceededWithoutSaving() {
        ConfirmationData data = confirmation(
                201L, 3L, "1300000", "1300000", InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE, AttributionMethod.DIRECT, "EVIDENCE"
        );
        given(mapper.findConfirmationData(101L)).willReturn(List.of(data));
        given(mapper.findAttributedContractNumbers(101L))
                .willReturn(List.of(new AttributedContractNo(3L, "CT-2026-0003")));
        given(mapper.findCapRuleSnapshot(101L, 201L)).willReturn(capRule("0", null));
        given(capCalculator.calculate(any())).willReturn(capCalculation(3L));

        TransactionPrecheckResponse response = service.precheck(101L);

        assertThat(response.confirmable()).isFalse();
        assertThat(response.blockers()).hasSize(1);
        assertThat(response.blockers().get(0).code()).isEqualTo("FGC-CAP-001");
        assertThat(response.blockers().get(0).message()).contains("108.333333");
        assertThat(response.capPreview()).hasSize(1);
        assertThat(response.capPreview().get(0).resultStatus()).isEqualTo(CapResultStatus.VIOLATION);
        assertThat(response.capPreview().get(0).usagePct()).isEqualTo("108.333333");
        assertPrecheckSavesNothing();
    }

    // 같은 지급 건에 대해 precheck의 판정과 confirm의 차단 판정이 일치해야 한다 (FGC-FUN-033 완료 조건)
    @Test
    void precheckVerdictMatchesConfirmRejection() {
        ConfirmationData data = confirmation(
                201L, 3L, "1300000", "1300000", InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE, AttributionMethod.DIRECT, "EVIDENCE"
        );
        given(mapper.findConfirmationData(101L)).willReturn(List.of(data));
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(List.of(data));
        given(mapper.findAttributedContractNumbers(101L))
                .willReturn(List.of(new AttributedContractNo(3L, "CT-2026-0003")));
        given(mapper.findCapRuleSnapshot(101L, 201L)).willReturn(capRule("0", null));
        given(capCalculator.calculate(any())).willReturn(capCalculation(3L));
        doAnswer(invocation -> {
            invocation.<CapCheckCommand>getArgument(0).setCapCheckId(56L);
            return null;
        }).when(mapper).insertCapCheck(any());

        TransactionPrecheckResponse preview = service.precheck(101L);

        assertThatThrownBy(() -> service.confirm(101L, null))
                .isInstanceOfSatisfying(FgcBusinessException.class, exception -> {
                    assertThat(exception.getErrorCode().getCode())
                            .isEqualTo(preview.blockers().get(0).code());
                    // blocker 문구 == confirm 실패 응답이 렌더링할 문구 (같은 카탈로그·같은 파라미터)
                    assertThat(messageResolver.resolve(exception.getErrorCode(), exception.getParams()))
                            .isEqualTo(preview.blockers().get(0).message());
                });

        ArgumentCaptor<CapCheckCommand> captor = ArgumentCaptor.forClass(CapCheckCommand.class);
        verify(mapper).insertCapCheck(captor.capture());
        assertThat(preview.capPreview().get(0).usagePct())
                .isEqualTo(captor.getValue().getUsagePct().toPlainString());
    }

    // FGC-FUN-033 · 제31조 게이트 ② — 귀속행 없는 DRAFT는 FGC-TRAN-002 blocker로 표시된다
    @Test
    void precheckReportsMissingAttributionBlocker() {
        given(mapper.findConfirmationData(101L)).willReturn(List.of(emptyDraftConfirmation()));

        TransactionPrecheckResponse response = service.precheck(101L);

        assertThat(response.confirmable()).isFalse();
        assertThat(response.blockers())
                .extracting(TransactionPrecheckResponse.Blocker::code)
                .containsExactly("FGC-TRAN-002");
        assertThat(response.capPreview()).isEmpty();
        verify(capCalculator, never()).calculate(any());
        assertPrecheckSavesNothing();
    }

    // FGC-FUN-033 · 제31조 게이트 ③·④ — 귀속합계 불일치와 REVIEW_REQUIRED 귀속을 한 번에 전부 수집한다 (fail-fast 아님)
    @Test
    void precheckCollectsMultipleBlockers() {
        ConfirmationData data = withPaymentAmount(confirmation(
                201L, 3L, "400000", "400000", InclusionDecisionStatus.REVIEW_REQUIRED,
                ExclusionType.NONE, AttributionMethod.MANUAL_REVIEW, "EVIDENCE"
        ), "500000");
        given(mapper.findConfirmationData(101L)).willReturn(List.of(data));
        given(mapper.findAttributedContractNumbers(101L))
                .willReturn(List.of(new AttributedContractNo(3L, "CT-2026-0003")));
        given(mapper.findCapRuleSnapshot(101L, 201L)).willReturn(capRule("0", null));
        given(capCalculator.calculate(any())).willReturn(capCalculation(3L));

        TransactionPrecheckResponse response = service.precheck(101L);

        assertThat(response.confirmable()).isFalse();
        // REVIEW_REQUIRED는 귀속행 상태·룰 불일치·병합 판정 세 게이트에서 재검출되지만
        // 같은 사유는 blockers에 한 번만 나타나야 한다 (중복 접기)
        assertThat(response.blockers())
                .extracting(TransactionPrecheckResponse.Blocker::code)
                .containsExactly("FGC-TRAN-003", "FGC-CAP-002");
        assertPrecheckSavesNothing();
    }

    // FGC-FUN-033·FGC-FUN-034 연계 — 미해결 CAP_VIOLATION 예외가 남아 있으면 FGC-CAP-003 blocker로 표시된다
    @Test
    void precheckReportsUnresolvedViolationBlocker() {
        ConfirmationData data = confirmation(
                201L, 3L, "500000", "500000", InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE, AttributionMethod.DIRECT, "EVIDENCE"
        );
        given(mapper.findConfirmationData(101L)).willReturn(List.of(data));
        given(capExceptionService.hasUnresolvedViolation(101L)).willReturn(true);
        given(mapper.findAttributedContractNumbers(101L))
                .willReturn(List.of(new AttributedContractNo(3L, "CT-2026-0003")));
        given(mapper.findCapRuleSnapshot(101L, 201L)).willReturn(capRule("0", null));
        given(capCalculator.calculate(any())).willReturn(capCalculation(3L));

        TransactionPrecheckResponse response = service.precheck(101L);

        assertThat(response.confirmable()).isFalse();
        assertThat(response.blockers())
                .extracting(TransactionPrecheckResponse.Blocker::code)
                .contains("FGC-CAP-003");
        // 지급 건 단위 판정이라 특정 계약·귀속행을 가리키면 안 된다 (엉뚱한 예외 건 안내 방지)
        TransactionPrecheckResponse.Blocker violation = response.blockers().stream()
                .filter(blocker -> "FGC-CAP-003".equals(blocker.code()))
                .findFirst()
                .orElseThrow();
        assertThat(violation.contractId()).isNull();
        assertThat(violation.transactionAttributionId()).isNull();
        assertPrecheckSavesNothing();
    }

    // FGC-FUN-033 — 분류정책이 없는 귀속행은 FGC-CAP-002 blocker로 표시되고 preview 행·한도 계산은 생략된다
    @Test
    void precheckReportsMissingCapRuleAndSkipsPreview() {
        ConfirmationData data = confirmation(
                201L, 3L, "500000", "500000", InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE, AttributionMethod.DIRECT, "EVIDENCE"
        );
        given(mapper.findConfirmationData(101L)).willReturn(List.of(data));
        given(mapper.findAttributedContractNumbers(101L))
                .willReturn(List.of(new AttributedContractNo(3L, "CT-2026-0003")));
        given(mapper.findCapRuleSnapshot(101L, 201L)).willReturn(null);

        TransactionPrecheckResponse response = service.precheck(101L);

        assertThat(response.confirmable()).isFalse();
        assertThat(response.blockers())
                .extracting(TransactionPrecheckResponse.Blocker::code)
                .containsExactly("FGC-CAP-002");
        assertThat(response.capPreview()).isEmpty();
        verify(capCalculator, never()).calculate(any());
        assertPrecheckSavesNothing();
    }

    // IF-API-24 — 비DRAFT는 미리보기 대상이 아니라 FGC-TRAN-005(409)로 거부된다
    @Test
    void precheckRejectsNonDraftPayment() {
        ConfirmationData confirmed = withStatus(confirmation(
                201L, 3L, "500000", "500000", InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE, AttributionMethod.DIRECT, "EVIDENCE"
        ), CommissionPaymentStatus.CONFIRMED);
        given(mapper.findConfirmationData(101L)).willReturn(List.of(confirmed));

        assertThatThrownBy(() -> service.precheck(101L))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(FgcErrorCode.TRAN_005));
        assertPrecheckSavesNothing();
    }

    // IF-API-24 — 미존재 지급 건은 FGC-COMMON-002(400), 이 경로에서도 아무것도 저장하지 않는다
    @Test
    void precheckRejectsUnknownPayment() {
        given(mapper.findConfirmationData(999L)).willReturn(List.of());

        assertThatThrownBy(() -> service.precheck(999L))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(FgcErrorCode.COMMON_002));
        assertPrecheckSavesNothing();
    }

    // FGC-FUN-033 — 정책 드리프트: 귀속 스냅샷(INCLUDED)과 룰 현재 판정(EXCLUDED)이 어긋나면
    // preview 판정은 검토필요로 강제되고 CAP-002 blocker가 수집된다 (게이지 "정상" 오인 방지 —
    // confirm은 이 행에서 계산에 도달하지 못하므로 NORMAL 게이지는 확정 경로에 존재하지 않는 값)
    @Test
    void precheckForcesReviewRequiredWhenRuleDisagreesWithSnapshot() {
        ConfirmationData data = confirmation(
                201L, 3L, "500000", "500000", InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE, AttributionMethod.DIRECT, "EVIDENCE"
        );
        given(mapper.findConfirmationData(101L)).willReturn(List.of(data));
        given(mapper.findAttributedContractNumbers(101L))
                .willReturn(List.of(new AttributedContractNo(3L, "CT-2026-0003")));
        given(mapper.findCapRuleSnapshot(101L, 201L)).willReturn(new CapRuleSnapshot(
                31L,
                41L,
                InclusionDecisionStatus.EXCLUDED,
                "정책 변경으로 제외",
                new BigDecimal("100000"),
                new BigDecimal("12"),
                new BigDecimal("90"),
                BigDecimal.ZERO,
                null
        ));
        given(capCalculator.calculate(any())).willReturn(capCalculation(3L));

        TransactionPrecheckResponse response = service.precheck(101L);

        assertThat(response.confirmable()).isFalse();
        assertThat(response.blockers())
                .extracting(TransactionPrecheckResponse.Blocker::code)
                .containsExactly("FGC-CAP-002");
        assertThat(response.capPreview()).hasSize(1);
        assertThat(response.capPreview().get(0).resultStatus())
                .isEqualTo(CapResultStatus.REVIEW_REQUIRED);
        assertPrecheckSavesNothing();
    }

    /** precheck의 무저장·무잠금 계약 — 확정 경로 전용 부작용이 하나도 호출되지 않아야 한다. */
    private void assertPrecheckSavesNothing() {
        verify(mapper, never()).insertCapCheck(any());
        verify(mapper, never()).insertCapCheckDetail(any());
        verify(mapper, never()).insertExceptionCase(any());
        verify(mapper, never()).confirm(any(), any(), any());
        verify(mapper, never()).lockAttributedContracts(any());
        verify(mapper, never()).findConfirmationDataForUpdate(any());
        verify(capExceptionService, never()).createIfNecessary(any());
    }

    private ConfirmationData withPaymentAmount(ConfirmationData data, String paymentAmount) {
        return new ConfirmationData(
                data.paymentId(),
                data.status(),
                new BigDecimal(paymentAmount),
                data.attributedAmount(),
                data.totalAttributedAmount(),
                data.confirmIdempotencyKey(),
                data.attributionDate(),
                data.attributionMonth(),
                data.transactionAttributionId(),
                data.contractId(),
                data.agentId(),
                data.paymentStage(),
                data.commissionItemId(),
                data.itemCode(),
                data.itemName(),
                data.policyVersionId(),
                data.inclusionDecisionStatus(),
                data.exclusionType(),
                data.inclusionDecisionReason(),
                data.allocationBasis(),
                data.evidenceRef(),
                data.attributionMethod()
        );
    }

    private ConfirmationData withPolicyVersion(ConfirmationData data, Long policyVersionId) {
        return new ConfirmationData(
                data.paymentId(), data.status(), data.amount(), data.attributedAmount(),
                data.totalAttributedAmount(), data.confirmIdempotencyKey(), data.attributionDate(),
                data.attributionMonth(), data.transactionAttributionId(), data.contractId(), data.agentId(),
                data.paymentStage(), data.commissionItemId(), data.itemCode(), data.itemName(), policyVersionId,
                data.inclusionDecisionStatus(), data.exclusionType(), data.inclusionDecisionReason(),
                data.allocationBasis(), data.evidenceRef(), data.attributionMethod()
        );
    }

    private ConfirmationData confirmation(
            Long attributionId,
            Long contractId,
            String amount,
            String total,
            InclusionDecisionStatus status,
            ExclusionType exclusionType,
            AttributionMethod method,
            String evidenceRef
    ) {
        return new ConfirmationData(
                101L,
                CommissionPaymentStatus.DRAFT,
                new BigDecimal(total),
                new BigDecimal(amount),
                new BigDecimal(total),
                null,
                LocalDate.of(2026, 7, 3),
                LocalDate.of(2026, 7, 1),
                attributionId,
                contractId,
                7L,
                PaymentStage.GA_TO_FC,
                11L,
                "BASE_COMMISSION",
                "FC 기본수수료",
                3L,
                status,
                exclusionType,
                "룰셋 판정",
                "DIRECT",
                evidenceRef,
                method
        );
    }

    private CapRuleSnapshot capRule(String existingAmount, BigDecimal complianceEvidenceAmount) {
        return new CapRuleSnapshot(
                31L,
                41L,
                InclusionDecisionStatus.INCLUDED,
                "활성 룰셋 산입",
                new BigDecimal("100000"),
                new BigDecimal("12"),
                new BigDecimal("90"),
                new BigDecimal(existingAmount),
                complianceEvidenceAmount
        );
    }

    private ConfirmationData withStatus(ConfirmationData data, CommissionPaymentStatus status) {
        return withConfirmationState(data, status, data.confirmIdempotencyKey());
    }

    private ConfirmationData withAllocationBasis(ConfirmationData data, String allocationBasis) {
        return new ConfirmationData(
                data.paymentId(),
                data.status(),
                data.amount(),
                data.attributedAmount(),
                data.totalAttributedAmount(),
                data.confirmIdempotencyKey(),
                data.attributionDate(),
                data.attributionMonth(),
                data.transactionAttributionId(),
                data.contractId(),
                data.agentId(),
                data.paymentStage(),
                data.commissionItemId(),
                data.itemCode(),
                data.itemName(),
                data.policyVersionId(),
                data.inclusionDecisionStatus(),
                data.exclusionType(),
                data.inclusionDecisionReason(),
                allocationBasis,
                data.evidenceRef(),
                data.attributionMethod()
        );
    }

    private ConfirmationData emptyDraftConfirmation() {
        return new ConfirmationData(
                101L,
                CommissionPaymentStatus.DRAFT,
                new BigDecimal("500000"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                7L,
                PaymentStage.GA_TO_FC,
                11L,
                "BASE_COMMISSION",
                "FC 기본수수료",
                3L,
                null,
                ExclusionType.NONE,
                null,
                null,
                null,
                null
        );
    }

    private ConfirmationData withConfirmationState(
            ConfirmationData data,
            CommissionPaymentStatus status,
            String idempotencyKey
    ) {
        return new ConfirmationData(
                data.paymentId(),
                status,
                data.amount(),
                data.attributedAmount(),
                data.totalAttributedAmount(),
                idempotencyKey,
                data.attributionDate(),
                data.attributionMonth(),
                data.transactionAttributionId(),
                data.contractId(),
                data.agentId(),
                data.paymentStage(),
                data.commissionItemId(),
                data.itemCode(),
                data.itemName(),
                data.policyVersionId(),
                data.inclusionDecisionStatus(),
                data.exclusionType(),
                data.inclusionDecisionReason(),
                data.allocationBasis(),
                data.evidenceRef(),
                data.attributionMethod()
        );
    }

    private CapCalculationResult capCalculation(Long contractId) {
        return capCalculation(contractId, CapResultStatus.NORMAL, Map.of());
    }

    private CapCalculationResult capCalculation(
            Long contractId,
            CapResultStatus resultStatus,
            Map<String, Object> snapshot
    ) {
        return capCalculation(contractId, resultStatus, snapshot, BigDecimal.ZERO);
    }

    private CapCalculationResult capCalculation(
            Long contractId,
            CapResultStatus resultStatus,
            Map<String, Object> snapshot,
            BigDecimal includedAmount
    ) {
        BigDecimal limitAmount = new BigDecimal("1200000");
        BigDecimal remainingAmount = limitAmount.subtract(includedAmount);
        BigDecimal usagePct = includedAmount.multiply(BigDecimal.valueOf(100))
                .divide(limitAmount, 6, RoundingMode.HALF_UP);
        return new CapCalculationResult(
                contractId,
                PaymentStage.GA_TO_FC,
                CapCheckKind.REALTIME,
                LocalDate.of(2026, 7, 1),
                31L,
                null,
                new BigDecimal("100000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                limitAmount,
                includedAmount,
                remainingAmount,
                usagePct,
                resultStatus,
                List.of(),
                snapshot
        );
    }

    private CommissionPaymentRow row(CommissionPaymentStatus status) {
        return new CommissionPaymentRow(
                101L,
                "GA_MANUAL_PAYMENT",
                "GA-2026-07-0001",
                3L,
                7L,
                11L,
                "BASE_COMMISSION",
                "FC 기본수수료",
                new BigDecimal("500000"),
                LocalDate.of(2026, 7, 1),
                "PAYMENT",
                LocalDate.of(2026, 7, 25),
                PaymentStage.GA_TO_FC,
                status,
                3L,
                "PAYMENT-EVIDENCE",
                "수기 등록",
                OffsetDateTime.parse("2026-07-01T09:00:00+09:00"),
                OffsetDateTime.parse("2026-07-01T09:00:00+09:00")
        );
    }

    private CommissionPaymentAttributionRow attributionRow(int sequence, Long contractId, String amount) {
        return new CommissionPaymentAttributionRow(
                sequence,
                contractId,
                LocalDate.of(2026, 7, 3),
                LocalDate.of(2026, 7, 1),
                new BigDecimal(amount),
                InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE,
                "룰셋 산입",
                "DIRECT",
                "EVIDENCE",
                AttributionMethod.APPROVED_ALLOCATION
        );
    }
}
