package com.susukkang.fgc.schedule.service;

import com.susukkang.fgc.base.mapper.AgentMapper;
import com.susukkang.fgc.common.code.*;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.contract.dto.ContractScheduleResponse;
import com.susukkang.fgc.contract.dto.InsuranceContract;
import com.susukkang.fgc.contract.mapper.ContractMapper;
import com.susukkang.fgc.policy.dto.ResolvedCommissionPolicy;
import com.susukkang.fgc.policy.dto.ResolvedCommissionRule;
import com.susukkang.fgc.policy.service.CommissionPolicyService;
import com.susukkang.fgc.schedule.code.ScheduleGenReason;
import com.susukkang.fgc.schedule.code.SchedulePurpose;
import com.susukkang.fgc.schedule.code.ScheduleRegime;
import com.susukkang.fgc.common.code.ScheduleHeaderStatus;
import com.susukkang.fgc.schedule.dto.*;
import com.susukkang.fgc.schedule.mapper.ScheduleMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final CommissionPolicyService commissionPolicyService;
    private final ScheduleMapper scheduleMapper;
    private final ContractMapper contractMapper;
    private final AgentMapper agentMapper;

    /**
     * 설명 : 검색 조건에 따라 스케줄 헤더를 조회한다.
     * 검색 조건과 현재 페이지 , 최대 스케줄 개수를 받아
     * 스케줄 헤더 목록을 출력하고 최대페이지 , 현재페이지를 PageResponse를 통해 출력
     *
     * @param condition 조회 조건
     * @param page 현재 페이지
     * @param size 한 페이지에 출력할 스케줄 헤더 수
     * @return List<ContractListDTO> 조회된 보험계약 목록
     * @author hjKang
     * @since 2026-08-05
     */
    public PageResponse<ScheduleHeaderResponse> selectByCondition(
            @Valid ScheduleSearchCondition condition, int page, int size) {
        //입력값 검증
        if (page < 1) {
            throw validationException(
                    "page",
                    "page는 1 이상이어야 합니다."
            );
        }

        if (size < 1 || size > 100) {
            throw validationException(
                    "size",
                    "size는 1 이상 100 이하여야 합니다."
            );
        }
        //검색 조건 검사
        if (condition == null) {
            condition = new ScheduleSearchCondition();
        }

        // 검색 용도가 지정되지 않으면 운영 스케줄만 조회한다.
        if (condition.getPurpose() == null) {
            condition.setPurpose(SchedulePurpose.OPERATIONAL);
        }
        // offset : DB가 앞에서 건널 뛸 행 개수 -> offset 번째 부터 조회함
        long offset = (long) (page - 1) * size;
        List<ScheduleHeaderResponse> scheduleHeaderList = scheduleMapper.selectByCondition(condition,size,offset);
        // 검색 조건에 해당하는 전체 스케줄 건수 조회
        long totalSchedules =
                scheduleMapper.countByCondition(condition);

        return PageResponse.of(
                scheduleHeaderList,
                page,
                size,
                totalSchedules,
                "scheduleHeaderId,desc"
        );
    }

    private FgcBusinessException validationException(String field, String detail) {
        return new FgcBusinessException(
                FgcErrorCode.COMMON_002,
                field,
                Map.of("field", field),
                detail
        );
    }
    /**
     * 설명 : 스케줄 헤더의 상세보기를 눌러 스케줄 상세보기를 조회한다.
     * @param scheduleHeaderId 스케줄 아이디
     * @return ScheduleDetailResponse 조회된 보험계약 목록
     * @author hjKang
     * @since 2026-08-09
     */
    public ScheduleDetailResponse selectScheduleDetailById(Long scheduleHeaderId) {
        // 스케줄 Id 검증 및 가져오기
        if (scheduleHeaderId == null) {
            throw validationException(
                    "scheduleHeaderId",
                    "스케줄 헤더 ID는 필수입니다."
            );
        }

        ScheduleDetailResponse detail  = scheduleMapper.selectScheduleDetailById(scheduleHeaderId);

        if (detail == null) {
            throw new FgcBusinessException(
                    FgcErrorCode.COMMON_002,
                    "scheduleHeaderId",
                    Map.of("scheduleHeaderId", scheduleHeaderId),
                    "해당 스케줄을 찾을 수 없습니다."
            );
        }

        // TODO(FUN-039, 1차) 스케줄 상태·조정·버전 관리
        // 스케줄 상태 조정 버전 가져오기

        return detail ;
    }
    /**
     * 설명 : 계약 ID에 따라 회차별 스케줄을 자동 생성한다
     * @param contract 계약 class
     * @return 새로 생성된 스케줄 헤더 ID 목록과 라인 수
     * @author hjKang
     * @since 2026-08-10
     */
    @Transactional
    public ScheduleGenerationResult generateSchedules(InsuranceContract contract) {
        // 입력값 검증
        if (contract == null || contract.getContractId() == null) {
            throw new FgcBusinessException(
                    FgcErrorCode.COMMON_002,
                    "contractId",
                    Map.of("contractId", ""),
                    "계약 ID가 존재하지 않습니다."
            );
        }

        Long contractId = contract.getContractId();

        // 계약 존재 여부 확인
        InsuranceContract savedContract = contractMapper.selectById(contractId);

        if (savedContract == null) {   //일단 보류 001은 계약 중복 코드이므로 이따 추가함
            throw new FgcBusinessException(
                    FgcErrorCode.CONT_001,
                    "contractId",
                    Map.of("contractId", contractId),
                    "계약ID가 존재하지 않습니다."
            );
        }

        List<PaymentStage> paymentStages = List.of(
                PaymentStage.INSURER_TO_GA,
                PaymentStage.GA_TO_FC
        );
        Map<PaymentStage, ResolvedCommissionPolicy> resolvedPolicies =
                new EnumMap<>(PaymentStage.class);

        // 두 지급단계 정책을 모두 검증한 다음 저장을 시작하여 부분 INSERT를 방지한다.
        for (PaymentStage paymentStage : paymentStages) {
            resolvedPolicies.put(
                    paymentStage,
                    resolvePolicyOrRegisterReview(contractId, paymentStage)
            );
        }

        Long lockedContractId =
                scheduleMapper.lockContractForScheduleGeneration(contractId);
        if (!Objects.equals(lockedContractId, contractId)) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_500);
        }

        List<Long> createdHeaderIds = new ArrayList<>();
        int createdLineCount = 0;
        for (PaymentStage paymentStage : paymentStages) {
            ResolvedCommissionPolicy policy = resolvedPolicies.get(paymentStage);

            if (policy != null) {
                CreatedSchedule createdSchedule = createSchedule(savedContract, policy);
                if (createdSchedule.scheduleHeaderId() != null) {
                    createdHeaderIds.add(createdSchedule.scheduleHeaderId());
                }
                createdLineCount += createdSchedule.createdLineCount();
            }
        }

        return new ScheduleGenerationResult(createdHeaderIds, createdLineCount);
    }

    /**
     * 설명 : 지급단계에 적용할 정책을 조회한다. 정책이 없거나 복수로 선택된 경우에는
     * 해당 지급단계의 스케줄 생성을 건너뛰고 exception_case에 검토 건을 등록한다.
     * 그 외의 정책·시스템 오류는 정상 실패 처리를 위해 상위로 전달한다.
     *
     * @param contractId 계약 ID
     * @param paymentStage 지급 단계
     * @return 적용 정책, 검토가 필요한 경우 null
     */
    private ResolvedCommissionPolicy resolvePolicyOrRegisterReview(
            Long contractId,
            PaymentStage paymentStage
    ) {
        try {
            ResolvedCommissionPolicy policy =
                    commissionPolicyService.resolveCurrentCommission(
                            contractId,
                            paymentStage
                    );
            validateResolvedPolicy(policy, paymentStage);
            return policy;
        } catch (FgcBusinessException exception) {
            Object reasonValue = exception.getParams().get("reason");
            String reason = reasonValue == null ? null : reasonValue.toString();

            if (!"POLICY_MISSING".equals(reason)
                    && !"POLICY_DUPLICATE".equals(reason)) {
                throw exception;
            }

            String title = "수수료 정책 검토 필요 - " + paymentStage.name();
            String description = exception.getDetail() == null
                    ? "예상 스케줄에 적용할 수수료 정책을 확정할 수 없습니다."
                    : exception.getDetail();

            int affectedRows = scheduleMapper.upsertPolicyReviewCase(
                    contractId,
                    paymentStage,
                    reason,
                    title,
                    description
            );

            if (affectedRows != 1) {
                throw new FgcBusinessException(FgcErrorCode.COMMON_500);
            }

            return null;
        }
    }

    /**
     * 설명 : 확정된 단일 정책을 기준으로 지급단계별 스케줄 헤더와 라인을 생성한다.
     *
     * @param contract 저장된 계약
     * @param policy 적용할 단일 정책
     * @return 생성된 헤더 ID와 저장된 라인 수
     */
    private CreatedSchedule createSchedule(
            InsuranceContract contract,
            ResolvedCommissionPolicy policy
    ) {
        Long activePolicyVersionId =
                scheduleMapper.selectActiveOperationalPolicyVersionId(
                        contract.getContractId(),
                        policy.getPaymentStage()
                );

        // 같은 정책으로 이미 생성된 활성 운영 스케줄은 다시 만들지 않는다.
        if (Objects.equals(activePolicyVersionId, policy.getPolicyVersionId())) {
            return new CreatedSchedule(null, 0);
        }

        int nextVersionNo = scheduleMapper.selectNextScheduleVersionNo(
                contract.getContractId(),
                policy.getPaymentStage()
        );
        if (nextVersionNo < 1) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_500);
        }

        if (activePolicyVersionId != null) {
            int deactivatedRows =
                    scheduleMapper.deactivateActiveOperationalSchedule(
                            contract.getContractId(),
                            policy.getPaymentStage()
                    );
            if (deactivatedRows != 1) {
                throw new FgcBusinessException(FgcErrorCode.COMMON_500);
            }
        }

        ScheduleHeaderInsertDTO header =
                createScheduleHeader(contract, policy, nextVersionNo);
        int headerRows = scheduleMapper.insertScheduleHeader(header);

        if (headerRows != 1 || header.getScheduleHeaderId() == null) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_500);
        }

        List<ScheduleLineInsertDTO> lines =
                createScheduleLines(contract, header, policy);
        int insertedLineCount = scheduleMapper.insertAllScheduleLines(lines);

        if (insertedLineCount != lines.size()) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_500);
        }

        return new CreatedSchedule(header.getScheduleHeaderId(), insertedLineCount);
    }

    /**
     * 계약 ID에 해당하는 운영용 예상 스케줄 헤더 목록을 조회한다.
     *
     * @param contractId 계약 ID
     * @return 계약에 연결된 운영용 예상 스케줄 헤더 목록
     */
    public ContractScheduleResponse selectByContractId(
            Long contractId,
            PaymentStage paymentStage
    ) {
        if (contractId == null) {
            throw validationException(
                    "contractId",
                    "계약 ID는 필수입니다."
            );
        }
        if (paymentStage == null) {
            throw validationException(
                    "paymentStage",
                    "지급 단계는 필수입니다."
            );
        }

        ScheduleDetailResponse detail = scheduleMapper.selectByContractIdAndPaymentStage(
                contractId,
                paymentStage
        );
        if (detail == null) {
            return ContractScheduleResponse.builder()
                    .headers(List.of())
                    .lines(List.of())
                    .build();
        }

        return ContractScheduleResponse.builder()
                .headers(List.of(detail.getHeader()))
                .lines(detail.getSchedules() == null ? List.of() : detail.getSchedules())
                .build();
    }

    /** 지급단계 한 건의 스케줄 생성 결과. */
    private record CreatedSchedule(Long scheduleHeaderId, int createdLineCount) {
    }
    /**
     * 설명 : 정책 조회 서비스에서 반환된 수수료 정책이 스케줄 생성에 사용 가능한지 검증한다.
     * 정책 버전, 정책 유형, 지급 단계 및 수수료 규칙 목록의 필수값을 확인하고,
     * 조회된 정책의 지급 단계가 요청한 지급 단계와 일치하는지 확인한다.
     *
     * @param policy 정책 조회 서비스에서 반환된 수수료 정책
     * @param expectedPaymentStage 요청한 지급 단계
     */
    private void validateResolvedPolicy(
            ResolvedCommissionPolicy policy,
            PaymentStage expectedPaymentStage
    ) {
        if (policy == null
                || policy.getPolicyVersionId() == null
                || policy.getPolicyType() == null
                || policy.getPaymentStage() == null
                || policy.getRules() == null
                || policy.getRules().isEmpty()) {
            throw new FgcBusinessException(
                    FgcErrorCode.COMMON_002,
                    "commissionPolicy",
                    Map.of(
                            "paymentStage",
                            expectedPaymentStage.name()
                    ),
                    "적용 가능한 수수료 정책 또는 규칙이 없습니다."
            );
        }

        if (policy.getPaymentStage() != expectedPaymentStage) {
            throw new FgcBusinessException(
                    FgcErrorCode.COMMON_002,
                    "paymentStage",
                    Map.of(
                            "expected", expectedPaymentStage.name(),
                            "actual", policy.getPaymentStage().name()
                    ),
                    "조회된 정책의 지급 단계가 요청과 일치하지 않습니다."
            );
        }

        validateNoOverlappingRules(policy);
    }

    /**
     * 설명 : 하나의 계약에 적용된 수수료 규칙 사이에 중복 회차가 존재하는지 검증한다.
     * 범용 규칙과 상품·조직 전용 규칙이 동시에 조회되더라도 같은 수수료 항목과
     * 지급 대상 직급의 회차가 겹치면 이중 스케줄이 생성될 수 있으므로 생성을 중단한다.
     * 규칙 우선순위에 따른 선택은 정책 조회 영역에서 명시적으로 해결해야 한다.
     *
     * @param policy 중복 여부를 검증할 적용 정책
     */
    private void validateNoOverlappingRules(ResolvedCommissionPolicy policy) {
        List<ResolvedCommissionRule> rules = policy.getRules();

        for (int i = 0; i < rules.size(); i++) {
            ResolvedCommissionRule current = rules.get(i);
            validateRuleInstallmentRange(current);

            for (int j = i + 1; j < rules.size(); j++) {
                ResolvedCommissionRule candidate = rules.get(j);
                validateRuleInstallmentRange(candidate);

                boolean sameTarget = Objects.equals(
                        current.getCommissionItemId(), candidate.getCommissionItemId()
                ) && current.getAgentRankCode() == candidate.getAgentRankCode();
                boolean overlaps = current.getInstallmentFrom() <= candidate.getInstallmentTo()
                        && candidate.getInstallmentFrom() <= current.getInstallmentTo();

                if (sameTarget && overlaps) {
                    throw new FgcBusinessException(
                            FgcErrorCode.COMMON_002,
                            "commissionRules",
                            Map.of(
                                    "policyVersionId", String.valueOf(policy.getPolicyVersionId()),
                                    "firstRuleId", String.valueOf(current.getCommissionRuleId()),
                                    "secondRuleId", String.valueOf(candidate.getCommissionRuleId())
                            ),
                            "동일한 수수료 항목과 지급 대상에 중복 적용되는 회차 규칙이 있습니다."
                    );
                }
            }
        }
    }

    /**
     * 설명 : 수수료 규칙의 적용 시작·종료 회차가 스케줄 생성 가능한 값인지 검증한다.
     *
     * @param rule 검증할 수수료 규칙
     */
    private void validateRuleInstallmentRange(ResolvedCommissionRule rule) {
        if (rule == null
                || rule.getInstallmentFrom() == null
                || rule.getInstallmentTo() == null
                || rule.getInstallmentFrom() < 1
                || rule.getInstallmentTo() < rule.getInstallmentFrom()) {
            throw new FgcBusinessException(
                    FgcErrorCode.COMMON_002,
                    "installmentRange",
                    Map.of(
                            "commissionRuleId",
                            rule == null ? "" : String.valueOf(rule.getCommissionRuleId())
                    ),
                    "수수료 규칙의 적용 회차 범위가 올바르지 않습니다."
            );
        }
    }

    /**
     * 설명 : 계약과 생성된 스케줄 헤더 , 사용되는 정책에 따라 회차별 스케줄 Line을 하나씩 생성하여 반환한다.
     *  정책과 수수료 유형, 월납보험료 등 입력 파라미터의 필드에 따라 예상액이 산출되는 방식이 다름
     * @param contract 현재 계약
     * @param header 생성된 스케줄의 헤더
     * @param policy 해당 계약에 적용되는 정책
     * @return 회차별 스케줄 Lines
     * @author hjKang
     * @since 2026-08-10
     */
    private List<ScheduleLineInsertDTO> createScheduleLines(InsuranceContract contract,ScheduleHeaderInsertDTO header, ResolvedCommissionPolicy policy) {
        List<ScheduleLineInsertDTO> lines = new ArrayList<>();
        int lineNo = 1;
        for (ResolvedCommissionRule rule : policy.getRules()) {
            BigDecimal basisAmount = resolveBasisAmount(contract, rule);
            BigDecimal expectedAmount = calculateExpectedAmount(basisAmount, rule);
            Long beneficiaryAgentId = resolveBeneficiaryAgentId(contract, policy, rule);
            for (int installmentNo = rule.getInstallmentFrom();
                 installmentNo <= rule.getInstallmentTo();
                 installmentNo++) {
                ScheduleLineInsertDTO line = ScheduleLineInsertDTO.builder()
                        .scheduleHeaderId(header.getScheduleHeaderId())
                        .lineNo(lineNo++)
                        .installmentNo(installmentNo)
                        .contractMonthNo(calculateContractMonthNo(installmentNo))
                        .dueDate(calculateDueDate(contract.getContractDate(),installmentNo))
                        .commissionItemId(rule.getCommissionItemId())
                        .beneficiaryAgentId(beneficiaryAgentId)
                        .basisCode(rule.getBasisCode())
                        .basisAmount(basisAmount)
                        .calculationType(rule.getCalculationType())
                        .ratePct(rule.getRatePct())
                        .fixedAmount(rule.getFixedAmount())
                        .expectedAmount(expectedAmount)
                        .roundingScale(rule.getRoundingScale())
                        .roundingMode(rule.getRoundingMode())
                        .paymentConditionCode(rule.getPaymentConditionCode())
                        .lineStatus(ScheduleLineStatus.PLANNED)
                        .sourceCommissionRuleId(rule.getCommissionRuleId())
                        .build();
                lines.add(line);
            }
        }
        return lines;
    }
    /**
     * 설명 : 수수료 규칙의 기준 코드를 이용해 계약에서 계산 기준금액을 가져온다.
     *
     * @param contract 계산 대상 계약
     * @param rule 수수료 계산 규칙
     * @return 수수료 계산 기준금액
     */
    private BigDecimal resolveBasisAmount(
            InsuranceContract contract,
            ResolvedCommissionRule rule
    ) {
        if (rule == null || rule.getCalculationType() == null) {
            throw new IllegalArgumentException(
                    "수수료 계산 방식이 없습니다."
            );
        }

        if (rule.getCalculationType() == CalculationType.FIXED) {
            if (rule.getFixedAmount() == null) {
                throw new IllegalArgumentException(
                        "정액 계산에 필요한 고정금액이 없습니다."
                );
            }
            return rule.getFixedAmount();
        }

        String basisCode = rule.getBasisCode();
        if (basisCode == null) {
            throw new IllegalArgumentException(
                    "수수료 계산 기준 코드가 없습니다."
            );
        }

        return switch (basisCode) {
            case "MONTHLY_EQUIVALENT_FIRST_PREMIUM" ->
                    contract.getMonthlyEquivalentFirstPremium();

            default -> throw new IllegalArgumentException(
                    "지원하지 않는 수수료 계산 기준입니다: "
                            + basisCode
            );
        };
    }
    /**
     * 설명 : 수수료 규칙의 계산 방식과 반올림 조건을 적용하여 스케줄 라인의 예상 지급액을 계산한다.
     * FIXED 방식은 정책에 설정된 고정금액을 사용하고,
     * RATE 방식은 기준금액에 정책 요율을 적용하여 예상 지급액을 계산한다.
     *
     * @param basisAmount 수수료 계산 기준금액
     * @param rule 예상 지급액 계산에 적용할 수수료 규칙
     * @return 반올림 정책이 적용된 예상 지급액
     * @author hjKang
     * @since 2026-08-10
     */
    private BigDecimal calculateExpectedAmount(
            BigDecimal basisAmount,
            ResolvedCommissionRule rule
    ) {
        validateRoundingPolicy(rule);

        if (rule.getCalculationType() == null) {
            throw new IllegalArgumentException(
                    "수수료 계산 방식이 없습니다."
            );
        }
        return switch (rule.getCalculationType()) {
            case FIXED -> {  //정책 유형이 고정된 값일 경우
                if (rule.getFixedAmount() == null) {
                    throw new IllegalArgumentException(
                            "정액 계산에 필요한 고정금액이 없습니다."
                    );
                }
                yield rule.getFixedAmount().setScale(
                        rule.getRoundingScale(),
                        rule.getRoundingMode()
                );
            }

            case RATE -> { // 정책 유형이 월납환산료 비례 일 경우
                if (basisAmount == null || rule.getRatePct() == null) {
                    throw new IllegalArgumentException(
                            "정률 계산에 필요한 기준금액 또는 요율이 없습니다."
                    );
                }
                yield basisAmount
                        .multiply(rule.getRatePct())
                        .movePointLeft(2)
                        .setScale(
                                rule.getRoundingScale(),
                                rule.getRoundingMode()
                        );
            }
        };
    }

    /**
     * 설명 : 예상 수수료 상세행에 적용할 반올림 정책을 검증한다.
     * 운영정책과 DB 제약에 따라 원 단위(scale 0) HALF_UP만 허용한다.
     *
     * @param rule 반올림 정책을 포함한 수수료 규칙
     */
    private void validateRoundingPolicy(ResolvedCommissionRule rule) {
        if (rule == null
                || rule.getRoundingScale() == null
                || rule.getRoundingMode() == null) {
            throw new FgcBusinessException(
                    FgcErrorCode.COMMON_002,
                    "roundingPolicy",
                    Map.of(
                            "commissionRuleId",
                            rule == null ? "" : String.valueOf(rule.getCommissionRuleId())
                    ),
                    "수수료 계산에 필요한 반올림 정책이 없습니다."
            );
        }

        if (rule.getRoundingScale() != 0
                || rule.getRoundingMode() != RoundingMode.HALF_UP) {
            throw new FgcBusinessException(
                    FgcErrorCode.COMMON_002,
                    "roundingPolicy",
                    Map.of(
                            "commissionRuleId", String.valueOf(rule.getCommissionRuleId()),
                            "roundingScale", rule.getRoundingScale(),
                            "roundingMode", rule.getRoundingMode().name()
                    ),
                    "예상 수수료는 상세행별 원 단위 HALF_UP 반올림만 허용합니다."
            );
        }
    }

    /**
     * 설명 : 현재 프로젝트의 예상 스케줄 산정 가정에 따라 계약일과 지급 회차를 기준으로
     * 지급 예정일을 계산한다. 실제 운영 지급일은 보험회사·GA별 지급기준이 다를 수 있으므로
     * 향후 paymentConditionCode 또는 별도 지급일 정책이 제공되면 해당 규칙으로 대체해야 한다.
     *
     * @param contractDate 계약일
     * @param installmentNo 지급 회차
     * @return 지급 예정일
     */
    private LocalDate calculateDueDate(LocalDate contractDate, int installmentNo) {
        if (contractDate == null){
            throw new IllegalArgumentException(
                "계약일은 필수 입니다."
            );
        }
        if (installmentNo < 1){
            throw new IllegalArgumentException(
                "지급 회차는 1이상이어야 합니다."
            );
        }

        return contractDate.plusMonths(installmentNo-1);
    }
    /**
     * 설명 : 계약, 지급 단계 및 수수료 규칙을 기준으로 실제 수수료 수령 설계사 ID를 결정한다.
     * 원수사→GA 단계는 개인 수령자가 없으므로 null을 반환하고,
     * GA→설계사 단계는 직급에 따라 모집설계사 또는 상위 조직의 활성 설계사를 조회한다.
     *
     * @param contract 수수료 스케줄을 생성할 계약
     * @param policy 계약에 적용된 수수료 정책
     * @param rule 적용할 수수료 규칙
     * @return 지급 대상 설계사 ID, 원수사→GA 단계이면 null
     */
    // FUN-008 : 설계사 조직 id 와 직급 코드, 계약일을 통해 활성화 되어있는 설계사 ID를 찾는다.
    private Long resolveBeneficiaryAgentId(
            InsuranceContract contract,
            ResolvedCommissionPolicy policy,
            ResolvedCommissionRule rule
    ) {
        if (contract == null || policy == null || rule == null) {
            throw new FgcBusinessException(
                    FgcErrorCode.COMMON_002,
                    "beneficiaryAgent",
                    Map.of(
                            "contract", String.valueOf(contract),
                            "policy", String.valueOf(policy),
                            "rule", String.valueOf(rule)
                    ),
                    "지급 대상 설계사를 결정하기 위한 정보가 없습니다."
            );
        }

        PaymentStage paymentStage = policy.getPaymentStage();

        if (paymentStage == null) {
            throw new FgcBusinessException(
                    FgcErrorCode.COMMON_002,
                    "paymentStage",
                    Map.of(
                            "policyVersionId",
                            String.valueOf(policy.getPolicyVersionId())
                    ),
                    "정책의 지급 단계 정보가 없습니다."
            );
        }

        // 원수사→GA 예상 수입은 개인 설계사 수령자가 없다.
        if (paymentStage == PaymentStage.INSURER_TO_GA) {
            return null;
        }

        AgentRankCode agentRankCode = rule.getAgentRankCode();

        if (agentRankCode == null) {
            throw new FgcBusinessException(
                    FgcErrorCode.COMMON_002,
                    "agentRankCode",
                    Map.of(
                            "commissionRuleId",
                            String.valueOf(rule.getCommissionRuleId())
                    ),
                    "수수료 규칙에 지급 대상 직급 정보가 없습니다."
            );
        }

        // FC 수수료는 계약에 등록된 모집설계사에게 지급한다.
        if (agentRankCode == AgentRankCode.FC) {
            if (contract.getAgentId() == null) {
                throw new FgcBusinessException(
                        FgcErrorCode.COMMON_002,
                        "agentId",
                        Map.of(
                                "contractId",
                                String.valueOf(contract.getContractId())
                        ),
                        "계약의 모집설계사 정보가 없습니다."
                );
            }

            return contract.getAgentId();
        }

        // 관리자 수수료는 계약 소속 조직부터 상위 조직으로 탐색하여
        // 규칙에 지정된 직급의 활성 설계사를 조회한다.
        return findActiveAgentId(
                contract.getContractDate(),
                contract.getOrganizationId(),
                agentRankCode
        );
    }
    /**
     * 설명 : 조직 ID, 직급 코드 및 계약일을 기준으로 해당 시점에 활성 상태인
     * 설계사 ID를 계약 소속 조직과 상위 조직에서 조회한다.
     *
     * @param contractDate 설계사 활성 여부를 판단할 기준일인 계약일
     * @param organizationId 조회를 시작할 계약 소속 조직 ID
     * @param agentRankCode 조회할 설계사 직급 코드
     * @return 조직과 직급에 해당하는 활성 설계사 ID
     */
    private Long findActiveAgentId(
            LocalDate contractDate,
            Long organizationId,
            AgentRankCode agentRankCode
    ) {
        if (contractDate == null
                || organizationId == null
                || agentRankCode == null) {
            throw new FgcBusinessException(
                    FgcErrorCode.COMMON_002,
                    "beneficiaryAgent",
                    Map.of(
                            "contractDate", String.valueOf(contractDate),
                            "organizationId", String.valueOf(organizationId),
                            "agentRankCode", String.valueOf(agentRankCode)
                    ),
                    "지급 대상 설계사 조회 조건이 올바르지 않습니다."
            );
        }
        Long agentId =
                agentMapper.findActiveAgentIdFromOrganizationHierarchy(
                        organizationId,
                        agentRankCode,
                        contractDate
                );
        if (agentId == null) {
            throw new FgcBusinessException(
                    FgcErrorCode.COMMON_002,
                    "beneficiaryAgentId",
                    Map.of(
                            "contractDate", contractDate,
                            "organizationId", organizationId,
                            "agentRankCode", agentRankCode.name()
                    ),
                    "조직과 직급에 해당하는 활성 설계사를 찾을 수 없습니다."
            );
        }
        return agentId;
    }
    /**
     * 설명 : 계약 정보와 지급 회차를 통해서 계약차월을 계산한다.
     * 단 현행스케줄은 지급회차와 계약차월이 동일 하므로 그대로 반환 이후 2차 때 확장 고려
     * @param installmentNo 지급회차
     * @return installmentNo 계약차월
     */
    private Integer calculateContractMonthNo(int installmentNo) {
        //FUN-036 현행 월납 스케줄은 지급회차와 계약차월이 동일 but 4년 7년 분급일 경우 상이 하므로 확장 해야함
        return installmentNo;
    }
    /**
     * 설명 : 생성된 계약과 적용되는 정책에 맞춰 스케줄 헤더를 생성한다.
     * @param contract 현재 계약
     * @param policy 계약에 적용되는 정책
     * @return 스케줄 헤더 DTO
     * @author hjKang
     * @since 2026-08-10
     */
    private ScheduleHeaderInsertDTO createScheduleHeader(
            InsuranceContract contract,
            ResolvedCommissionPolicy policy,
            int scheduleVersionNo
    ) {
        ScheduleHeaderInsertDTO header = ScheduleHeaderInsertDTO.builder()
                .contractId(contract.getContractId())
                .paymentStage(policy.getPaymentStage())
                .policyVersionId(policy.getPolicyVersionId())
                .scheduleVersionNo(scheduleVersionNo)
                .schedulePurpose(SchedulePurpose.OPERATIONAL)
                .scheduleRegime(determineScheduleRegime(policy))
                .status(ScheduleHeaderStatus.PLANNED)
                .activeYn(Boolean.TRUE)
                .generationReason(ScheduleGenReason.CONTRACT_CREATED)
                .build();
        return header;
    }
    /**
     * 설명 : 들어온 정책의 유형에 따라 매핑되어서 스케줄 헤더의 regime로 반환된다.
     * 현행 , 4년 , 7년에 따라 맞춰서 반환하며 다른 commission일 경우 에러를 반환함.
     * @param policy 조회된 정책과 상품 판매버전의 적용 체계
     * @return 스케줄 헤더 DTO
     */
    private ScheduleRegime determineScheduleRegime(ResolvedCommissionPolicy policy) {
        if (policy.getScheduleRegime() != null) {
            return policy.getScheduleRegime();
        }

        PolicyType policyType = policy.getPolicyType();
        return switch(policyType){
            case CURRENT_COMMISSION->ScheduleRegime.CURRENT;//현행
            case FOUR_YEAR_COMMISSION->ScheduleRegime.FOUR_YEAR_2027; //4년 분급
            case SEVEN_YEAR_COMMISSION->ScheduleRegime.SEVEN_YEAR_2029; //7년분급
            default -> throw new IllegalArgumentException(
                    "스케줄을 생성할 수 없는 정책 유형입니다: " + policyType
            );
        };
    }
}
