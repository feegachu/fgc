package com.susukkang.fgc.contract.service;

import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.util.MoneyUtil;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.contract.domain.DataOrigin;
import com.susukkang.fgc.contract.domain.PaymentCycleCode;
import com.susukkang.fgc.contract.domain.PremiumConversionRuleCode;
import com.susukkang.fgc.contract.dto.*;
import com.susukkang.fgc.contract.mapper.ContractMapper;
import com.susukkang.fgc.schedule.service.ScheduleService;
import com.susukkang.fgc.schedule.dto.ScheduleGenerationResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 설명 : 보험계약 등록·조회·수정 업무를 처리한다.
 *
 * @author hjKang
 * @since 2026-08-05
 * @version 1.2
 */
@Service
@Validated
@RequiredArgsConstructor
public class ContractService {

    private final ContractMapper contractMapper;
    private final CapCheckService capCheckService;
    private final ScheduleService scheduleService;
    /**
     * 설명 : 검색 조건에 따라 계약을 조회한다.
     * 검색 조건과 현재 페이지 , 최대 계약수를 받아
     * 보험 계약 목록을 출력하고 최대페이지 , 현재페이지를 PageResponse를 통해 출력
     *
     * @param condition 조회 조건
     * @param page 현재 페이지
     * @param size 한 페이지에 출력할 계약 수
     * @return List<ContractListDTO> 조회된 보험계약 목록
     * @author hjKang
     * @since 2026-08-05
     */
    public PageResponse<ContractView> selectByCondition(ContractSearchCondition condition, int page, int size) {
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
        // offset : DB가 앞에서 건널 뛸 행 개수 -> offset 번째 부터 조회함
        long offsetLong = (long)( page - 1 ) * size;

        if (offsetLong > Integer.MAX_VALUE) {
            throw validationException(
                    "page",
                    "요청할 수 있는 페이지 범위를 초과했습니다."
            );
        }
        int offset = (int) offsetLong;

        List<ContractView> contractViewList = contractMapper.selectByCondition(condition,size,offset);
        // 검색조건에 해당하는 전체 계약 건수 조회
        long totalContracts =
                contractMapper.countByCondition(condition);

        return PageResponse.of(
                contractViewList,
                page,
                size,
                totalContracts,
                "contractId,desc"
        );
    }

    /**
     * 설명 : 보험계약 등록 요청을 검증하고 계약을 저장한다.
     *
     * @param request 보험계약 등록 요청
     * @return 등록된 보험계약 정보
     * @author hjKang
     * @since 2026-08-05
     */
    @Transactional
    public ContractResponse createContract(ContractCreateRequest request) {
        validateInput(request); //검증

        // 납입 주기 -> 환산 코드 결정
        PremiumConversionRuleCode conversionRuleCode =
                determineConversionRuleCode(
                        request.getPaymentCycleCode()
                );
        // 환산 코드 -> 주기별 보험료 계산
        BigDecimal premiumPerCycleAmount =
                calculatePremiumPerCycleAmount(
                        conversionRuleCode,
                        request.getMonthlyEquivalentFirstPremium()
                );
        InsuranceContract insuranceContract =
                InsuranceContract.builder()
                        .insurerId(request.getInsurerId())  //보험사 ID
                        .productOfferingId(request.getProductOfferingId()) //상품 ID
                        .contractNo(request.getContractNo()) //계약 번호
                        .contractDate(request.getContractDate()) //계약일
                        .agentId(request.getAgentId()) //설계사 ID
                        .organizationId(request.getOrganizationId()) //조직 ID
                        .premiumPerCycleAmount(premiumPerCycleAmount) //주기별 보험료 (환산값)
                        .firstPremiumAmount(request.getFirstPremiumAmount()) //초회 보험료
                        .monthlyEquivalentFirstPremium(request.getMonthlyEquivalentFirstPremium()) //월납 환산보험료
                        .premiumConversionRuleCode(conversionRuleCode) //환산 코드
                        .paymentCycleCode(request.getPaymentCycleCode()) //주기 코드
                        .paymentTermMonths(request.getPaymentTermMonths()) //납입기간
                        .standardSurrenderDeductionAmount(request.getStandardSurrenderDeductionAmount()) //해약공제액
                        .currentStatus(request.getContractStatus()) //계약 상태
                        .dataOrigin(DataOrigin.MANUAL) // 원본 데이터 출처 : 직접입력 : MANUAL
                        .build();

        int insertedRows = contractMapper.insertContract(insuranceContract);
        //삽입 에러 검증
        if (insertedRows != 1) {
            throw new FgcBusinessException(
                    FgcErrorCode.COMMON_500
            );
        }

        // FUN-036: 계약 저장과 같은 트랜잭션에서 양방향 예상 스케줄을 생성한다.
        // 정책 없음·중복 지급단계는 exception_case 검토 큐에 등록되고 생성에서 제외된다.
        ScheduleGenerationResult scheduleResult =
                scheduleService.generateSchedules(insuranceContract);

        /*
         * TODO(FUN-030, REG-08~11)
         * 계약 저장 및 예상 스케줄 생성 완료 후 다음 양방향 1,200% 한도 검증을 수행한다.
         * 1. 보험회사 → GA (PaymentStage.INSURER_TO_GA)
         * 2. GA → FC     (PaymentStage.GA_TO_FC)
         * 각 단계별 CapCalculationCommand를 생성하여
         * capCheckService.calculateAndSave()를 호출한다.
         */

        // TODO(FUN-026, 2차): 계약 생성 상태 사건 이력을 등록한다.


        return ContractResponse.builder()
                .contractId(insuranceContract.getContractId())
                .scheduleHeaderIds(scheduleResult.scheduleHeaderIds())
                .build();
    }

    /**
     * 설명 : 계약 등록 요청의 업무 유효성을 검증한다.
     *
     * @param request 보험계약 등록 요청
     * @author hjKang
     * @since 2026-08-05
     */
    private void validateInput(ContractCreateRequest request) {
        if (request == null) {
            throw validationException(
                    "request",
                    "계약 등록 요청이 없습니다."
            );
        }

        validateContractDate(request); //계약 일자 적합한지 확인
        validateInsurerAndProduct(request); //보험사 , 보험상품 유무 확인
        validateAgentAndOrganization(request); // 직원 , 조직 유무 확인
        validateDuplicateContract(request); // 계약번호 중복 확인
    }

    private void validateContractDate(ContractInput request) {
        if (request.getContractDate().isAfter(LocalDate.now())) {
            throw new FgcBusinessException(
                    FgcErrorCode.CONT_002,
                    "contractDate",
                    Map.of(
                            "contractDate",
                            request.getContractDate()
                    ),
                    "계약일은 미래 날짜일 수 없습니다."
            );
        }
    }

    private void validateInsurerAndProduct(ContractInput  request) {
        if (!contractMapper.existsInsurer(request.getInsurerId())) {
            throw validationException(
                    "insurerId",
                    "존재하지 않는 보험회사입니다."
            );
        }

        if (!contractMapper.existsProductOffering(
                request.getInsurerId(),
                request.getProductOfferingId(),
                request.getContractDate()
        )) {
            throw validationException(
                    "productOfferingId",
                    "선택한 보험회사의 상품 판매 버전이 아닙니다."
            );
        }
    }

    private void validateAgentAndOrganization(ContractInput  request) {
        if (!contractMapper.existsAgent(
                request.getAgentId(),
                request.getContractDate()
        )) {
            throw validationException(
                    "agentId",
                    "존재하지 않는 설계사입니다."
            );
        }

        if (!contractMapper.existsAgentOrganization(
                request.getAgentId(),
                request.getOrganizationId(),
                request.getContractDate()
        )) {
            throw validationException(
                    "organizationId",
                    "설계사의 소속 조직이 일치하지 않습니다."
            );
        }
    }

    private void validateDuplicateContract(ContractInput request) {
        if (contractMapper.existsContractNo(
                request.getInsurerId(),
                request.getContractNo()
        )) {
            throw new FgcBusinessException(
                    FgcErrorCode.CONT_001,
                    "contractNo",
                    Map.of(
                            "contractNo",
                            request.getContractNo()
                    ),
                    "이미 등록된 계약번호입니다."
            );
        }
    }

    private FgcBusinessException validationException(
            String field,
            String detail
    ) {
        return new FgcBusinessException(
                FgcErrorCode.COMMON_002,
                field,
                Map.of("field", field),
                detail
        );
    }
    /**
     * 설명 : 납입주기에 따라 환산 코드를 결정하는 메서드
     *
     * @param  paymentCycleCode 납입주기 코드
     * @return PremiumConversionRuleCode 환산 코드
     * @author hjKang
     * @since 2026-08-06
     */
    private PremiumConversionRuleCode determineConversionRuleCode(
            PaymentCycleCode paymentCycleCode
    ) {
        return switch (paymentCycleCode) {
            case MONTHLY ->
                    PremiumConversionRuleCode.MONTHLY_AS_IS;

            case QUARTERLY ->
                    PremiumConversionRuleCode.MONTHLY_TO_QUARTERLY_X3;

            case SEMI_ANNUAL ->
                    PremiumConversionRuleCode.MONTHLY_TO_SEMI_ANNUAL_X6;

            case ANNUAL ->
                    PremiumConversionRuleCode.MONTHLY_TO_ANNUAL_X12;

            case SINGLE, OTHER ->
                    throw new FgcBusinessException(
                            FgcErrorCode.COMMON_002,
                            "paymentCycleCode",
                            Map.of(
                                    "field", "paymentCycleCode",
                                    "paymentCycleCode", paymentCycleCode
                            ),
                            "주기별 보험료를 역산할 수 없는 납입주기입니다."
                    );
        };
    }
    /**
     * 설명 : 주기별 납입보험료를 월납환산보험료 * 보험료 환산 규칙으로 역산하여 계산한다
     *
     * @param conversionRuleCode 보험료 환산 규칙
     * @param monthlyEquivalentFirstPremium 월납환산보험료
     * @return 주기별 납입보험료
     * @author hjKang
     * @since 2026-08-06
     */
    private BigDecimal calculatePremiumPerCycleAmount(
            PremiumConversionRuleCode conversionRuleCode,
            BigDecimal monthlyEquivalentFirstPremium
    ) {
        return switch (conversionRuleCode) {
            case MONTHLY_AS_IS ->
                    MoneyUtil.roundWon(
                            monthlyEquivalentFirstPremium
                    );

            case MONTHLY_TO_QUARTERLY_X3 ->
                    MoneyUtil.multiplyAndRound(
                            monthlyEquivalentFirstPremium,
                            BigDecimal.valueOf(3)
                    );

            case MONTHLY_TO_SEMI_ANNUAL_X6 ->
                    MoneyUtil.multiplyAndRound(
                            monthlyEquivalentFirstPremium,
                            BigDecimal.valueOf(6)
                    );

            case MONTHLY_TO_ANNUAL_X12 ->
                    MoneyUtil.multiplyAndRound(
                            monthlyEquivalentFirstPremium,
                            BigDecimal.valueOf(12)
                    );
        };
    }
    /**
     * 설명 : 계약 상세보기 서비스
     *       현재는 계약 기본정보를 반환한다.
     *       계약별 계약상태 사건 이력은 FUN-026 2차에서 포함한다.
     * @param  contractId 계약 ID
     * @return 계약 기본정보
     * @author hjKang
     * @since 2026-08-05
     */
    @Transactional(readOnly = true)
    public ContractDetailResponse selectContractDetailById(Long contractId) {
        // 계약 Id 검증 및 가져오기
        ContractDetailResponse detail = contractMapper.selectContractDetailById(contractId);
        if (detail == null) {
            throw validationException(
                    "contractId",
                    "존재하지 않는 보험계약입니다."
            );
        }
//        // TODO(FUN-026, 2차): 계약상태 사건 이력을 조회하여 상세 응답에 포함한다.
//        List<ContractStatusEventResponse> statusEvents =
//                contractStatusEventMapper.selectByContractId(contractId);
//        // ContractDetail에 Build 후 반환
//        detail.setContractStatusEventResponses(statusEvents);
        return detail;
    }

    /**
     * 설명 : 계약 수정
     * 요청한 Request 검증 및 해당 계약 ID를 수정한다.
     * 기존 스케줄은 그대로 두기 + active_yn = false
     * 새로운 스케줄 헤더 + Line 생성
     *
     * @param  id,request 계약 ID와 요청 정보
     * @return 계약ID와 새로운스케줄 ID List
     * @author hjKang
     * @since 2026-08-05
     */
    @Transactional
    public ContractResponse updateContract(Long id, ContractUpdateRequest request) {
        // 계약 Id 검증 및 계약 및 스케줄 정보 가져오기
        InsuranceContract currentContract = contractMapper.selectById(id); //기존 계약 정보

        if (currentContract == null) {   //검증
            throw validationException(
                    "contractId",
                    "존재하지 않는 보험계약입니다."
            );
        }
        validateInputForUpdate(currentContract,request); //수정값 유효성 검사

        // 납입 주기 -> 환산 코드 결정
        PremiumConversionRuleCode conversionRuleCode =
                determineConversionRuleCode(
                        request.getPaymentCycleCode()
                );
        // 환산 코드 -> 주기별 보험료 계산
        BigDecimal premiumPerCycleAmount =
                calculatePremiumPerCycleAmount(
                        conversionRuleCode,
                        request.getMonthlyEquivalentFirstPremium()
                );

        // 수정할 계약 객체 생성
        InsuranceContract updatedContract = InsuranceContract.builder()
                .contractId(id)    //계약 id - 유지
                .contractNo(request.getContractNo()) // 계약 번호 변경가능
                .insurerId(request.getInsurerId()) //보험사 ID
                .productOfferingId(request.getProductOfferingId()) //제품 ID
                .contractDate(request.getContractDate()) //계약 일자
                .agentId(request.getAgentId()) //설계사 ID
                .organizationId(request.getOrganizationId()) //조직 ID
                .premiumPerCycleAmount(premiumPerCycleAmount) //주기 보험료
                .firstPremiumAmount(request.getFirstPremiumAmount()) // 초회 보험료
                .monthlyEquivalentFirstPremium(request.getMonthlyEquivalentFirstPremium()) //월납 환산 보험료
                .premiumConversionRuleCode(conversionRuleCode) //납입 주기 변환 코드
                .paymentCycleCode(request.getPaymentCycleCode()) //납입 주기 코드
                .paymentTermMonths(request.getPaymentTermMonths())  //납입 기간
                .standardSurrenderDeductionAmount(request.getStandardSurrenderDeductionAmount())//해약공제액
                .currentStatus(request.getContractStatus())  //계약 상태
                .dataOrigin(currentContract.getDataOrigin()) // 데이터 출처
                .build();

        int updatedRows = contractMapper.updateContract(updatedContract);

        if (updatedRows != 1) {
            throw new FgcBusinessException(
                    FgcErrorCode.COMMON_500
            );
        }
        /*
         * TODO(FUN-036)
         * 기존 활성 스케줄을 비활성화하고
         * 새 버전의 스케줄 헤더 및 라인을 생성한다.
         */

        /*
         * TODO(FUN-030, REG-08~11)
         * FUN-036에서 새 스케줄 생성이 끝난 다음
         * 보험회사 → GA와 GA → FC를 구분하여 각각
         * capCheckService.calculateAndSave()를 호출한다.
         */

        /*
         * TODO(FUN-026, 2차)
         * existingContract.getCurrentStatus()와
         * request.getContractStatus()가 다른 경우
         * 계약상태 사건 이력을 등록한다.
         */

        return ContractResponse.builder()
                .contractId(id)
                .scheduleHeaderIds(List.of())
                .build();
    }
    /**
     * 설명 : 수정 요청 값을 검증하는 함수
     *
     * @param
     * @return 
     * @author hjKang
     * @since 2026-08-05
     */
    private void validateInputForUpdate(InsuranceContract currentContract,ContractUpdateRequest request) {
        validateContractDate(request); //계약 일자 적합한지 확인
        validateInsurerAndProduct(request); //보험사 , 보험상품 유무 확인
        validateAgentAndOrganization(request); // 직원 , 조직 유무 확인
        validateContractIdentityForUpdate(currentContract,request); //원수사 , 계약 번호 중복 확인
    }
    /**
     * 설명 : 계약 수정시 기존의 계약데이터의 원수사 , 계약 번호가 같을 경우 skip , 다를경우 중복 검사를 시행하는 함수
     *
     * @param  currentContract : 기존 계약 데이터
     * @param  request : 변경할 계약 데이터
     * @author hjKang
     * @since 2026-08-06
     */
    private void validateContractIdentityForUpdate(InsuranceContract currentContract, ContractUpdateRequest request) {
        boolean insurerChanged =
                !Objects.equals(
                        currentContract.getInsurerId(),
                        request.getInsurerId()
                );

        boolean contractNoChanged =
                !Objects.equals(
                        currentContract.getContractNo(),
                        request.getContractNo()
                );

        // 원수사와 계약번호가 모두 그대로면 자기 자신이므로 중복 검사 생략
        if (!insurerChanged && !contractNoChanged) {
            return;
        }

        // 둘 중 하나라도 변경되면 새 조합의 중복 여부 검사
        if (contractMapper.existsContractNo(
                request.getInsurerId(),
                request.getContractNo()
        )) {
            throw validationException(
                    "contractNo",
                    "해당 보험사에 이미 등록된 계약번호입니다."
            );
        }
    }
}
