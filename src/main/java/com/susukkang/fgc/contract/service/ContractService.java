package com.susukkang.fgc.contract.service;

import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.util.MoneyUtil;
import com.susukkang.fgc.contract.domain.DataOrigin;
import com.susukkang.fgc.contract.domain.PaymentCycleCode;
import com.susukkang.fgc.contract.domain.PremiumConversionRuleCode;
import com.susukkang.fgc.contract.dto.*;
import com.susukkang.fgc.contract.mapper.ContractMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 설명 : 보험계약 등록·조회·수정 업무를 처리한다.
 *
 * @author hjKang
 * @since 2026-08-05
 * @version 1.2
 */
@Service
@RequiredArgsConstructor
public class ContractService {

    private final ContractMapper contractMapper;
    private final CapCheckService capCheckService;
    /**
     * 설명 : 검색 조건에 따라 계약을 조회한다.
     *
     * @param  condition 조회 조건
     * @return List<ContractListDTO> 조회된 보험계약 목록
     * @author hjKang
     * @since 2026-08-05
     */
    public List<ContractView> selectByCondition(ContractSearchCondition condition) {
        return contractMapper.selectByCondition(condition);
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
    public ResponseContract createContract(ContractCreateRequest request) {
        validateInput(request); //검증

        // 월환산납입보험료 = 주기별 납입보험료 / 주기
        BigDecimal monthlyEquivalentFirstPremium =
                calculateMonthlyEquivalentFirstPremium(
                        request.getPaymentCycleCode(),
                        request.getFirstPremiumAmount()
                );
        // 월 환산 보험료를 어떤 규칙을 사용했는지
        PremiumConversionRuleCode conversionRuleCode =
                determineConversionRuleCode(
                        request.getPaymentCycleCode()
                );
        InsuranceContract insuranceContract =
                InsuranceContract.builder()
                        .insurerId(request.getInsurerId())
                        .productOfferingId(request.getProductOfferingId())
                        .contractNo(request.getContractNo())
                        .contractDate(request.getContractDate())
                        .agentId(request.getAgentId())
                        .organizationId(request.getOrganizationId())
                        .premiumPerCycleAmount(
                                request.getPremiumPerCycleAmount()
                        )
                        .firstPremiumAmount(
                                request.getFirstPremiumAmount()
                        )
                        .monthlyEquivalentFirstPremium(
                                monthlyEquivalentFirstPremium
                        )
                        .premiumConversionRuleCode(
                                conversionRuleCode
                        )
                        .paymentCycleCode(
                                request.getPaymentCycleCode()
                        )
                        .paymentTermMonths(
                                request.getPaymentTermMonths()
                        )
                        .standardSurrenderDeductionAmount(
                                request.getStandardSurrenderDeductionAmount()
                        )
                        .currentStatus(
                                request.getContractStatus()
                        )
                        .dataOrigin(DataOrigin.MANUAL) // 직접 입력 이므로
                        .build();

        int insertedRows = contractMapper.insertContract(insuranceContract);
        //삽입 에러 검증
        if (insertedRows != 1) {
            throw new FgcBusinessException(
                    FgcErrorCode.COMMON_500
            );
        }

        // 예상 스케줄 생성 FUN-036 1차

        // 1200% 한도 검증 FUN-030 , REG-08~11 양방향 1200% 검증
        // 1200% 한도 검증 1차 INS-GA
        CapCalculationCommand command =
                CapCalculationCommand.realtime(
                        insuranceContract.getContractId(),
                        PaymentStage.GA_TO_FC,
                        LocalDate.now()
                );

        capCheckService.calculateAndSave(command);
        //  1200% 한도 검증 1차 GA-FC


        // 계약상태 사건 이력 등록 FUN-026 2차


        return ResponseContract.builder()
                .contractId(insuranceContract.getContractId())
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
                request.getProductOfferingId()
        )) {
            throw validationException(
                    "productOfferingId",
                    "선택한 보험회사의 상품 판매 버전이 아닙니다."
            );
        }
    }

    private void validateAgentAndOrganization(ContractInput  request) {
        if (!contractMapper.existsAgent(request.getAgentId())) {
            throw validationException(
                    "agentId",
                    "존재하지 않는 설계사입니다."
            );
        }

        if (!contractMapper.existsAgentOrganization(
                request.getAgentId(),
                request.getOrganizationId()
        )) {
            throw validationException(
                    "organizationId",
                    "설계사의 소속 조직이 일치하지 않습니다."
            );
        }
    }

    private void validateDuplicateContract(ContractCreateRequest request) {
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
     * 설명 : 초회보험료를 월납 기준 보험료로 환산한다.
     *
     * @param paymentCycleCode 납입주기
     * @param firstPremiumAmount 초회보험료
     * @return 월납환산 초회보험료
     * @author hjKang
     * @since 2026-08-05
     */
    private BigDecimal calculateMonthlyEquivalentFirstPremium(
            PaymentCycleCode paymentCycleCode,
            BigDecimal firstPremiumAmount
    ) {
        return switch (paymentCycleCode) {
            case MONTHLY ->
                    MoneyUtil.roundWon(firstPremiumAmount);
            case QUARTERLY ->
                    MoneyUtil.divideAndRound(
                            firstPremiumAmount,
                            BigDecimal.valueOf(3)
                    );
            case SEMI_ANNUAL ->
                    MoneyUtil.divideAndRound(
                            firstPremiumAmount,
                            BigDecimal.valueOf(6)
                    );
            case ANNUAL ->
                    MoneyUtil.divideAndRound(
                            firstPremiumAmount,
                            BigDecimal.valueOf(12)
                    );
            case SINGLE, OTHER ->
                    throw new FgcBusinessException(
                            FgcErrorCode.COMMON_002,
                            "paymentCycleCode",
                            Map.of(
                                    "field", "paymentCycleCode",
                                    "paymentCycleCode", paymentCycleCode
                            ),
                            "월납환산을 지원하지 않는 납입주기입니다."
                    );
        };
    }

    /**
     * 설명 : 납입주기에 해당하는 월납환산 규칙을 반환한다.
     *
     * @param paymentCycleCode 납입주기
     * @return 월납환산 규칙
     * @author hjKang
     * @since 2026-08-05
     */
    private PremiumConversionRuleCode determineConversionRuleCode(
            PaymentCycleCode paymentCycleCode
    ) {
        return switch (paymentCycleCode) {
            case MONTHLY ->
                    PremiumConversionRuleCode.MONTHLY_AS_IS;

            case QUARTERLY ->
                    PremiumConversionRuleCode.QUARTERLY_DIV_3;

            case SEMI_ANNUAL ->
                    PremiumConversionRuleCode.SEMI_ANNUAL_DIV_6;

            case ANNUAL ->
                    PremiumConversionRuleCode.ANNUAL_DIV_12;

            case SINGLE, OTHER ->
                    throw new FgcBusinessException(
                            FgcErrorCode.COMMON_002,
                            "paymentCycleCode",
                            Map.of("field", "paymentCycleCode"),
                            "월납환산 규칙이 없는 납입주기입니다."
                    );
        };
    }
    /**
     * 설명 : 계약 상세보기 서비스
     *       계약의 기본정보 + 계약별 계약상태 사건 이력 List를 반환한다.
     * @param  contractId 계약 ID
     * @return 계약 기본 + 계약별 계약상태 사건 이력
     * @author hjKang
     * @since 2026-08-05
     */
    @Transactional(readOnly = true)
    public ResponseContractDetail selectContractDetailById(Long contractId) {
        // 계약 Id 검증 및 가져오기
        ResponseContractDetail detail = contractMapper.selectContractDetailById(contractId);
        if (detail == null) {
            throw validationException(
                    "contractId",
                    "존재하지 않는 보험계약입니다."
            );
        }
//        // 계약상태 사건 이력 가져오기 2차
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
    public ResponseContract updateContract(Long id, ContractUpdateRequest request) {
        // 계약 Id 검증 및 계약 및 스케줄 정보 가져오기
        InsuranceContract currentContract = contractMapper.selectById(id); //기존 계약 정보

        if (currentContract == null) {   //검증
            throw validationException(
                    "contractId",
                    "존재하지 않는 보험계약입니다."
            );
        }
        validateInputForUpdate(request); //수정값 유효성 검사

        // 월납환산보험료 계산
        BigDecimal monthlyEquivalentFirstPremium =
                calculateMonthlyEquivalentFirstPremium(
                        request.getPaymentCycleCode(),
                        request.getFirstPremiumAmount()
                );
        // 납입주기 -> 납입주기 변환 코드 매핑
        PremiumConversionRuleCode conversionRuleCode =
                determineConversionRuleCode(
                        request.getPaymentCycleCode()
                );

        // 수정할 계약 객체 생성
        InsuranceContract updatedContract = InsuranceContract.builder()
                .contractId(id)    //계약 id - 유지
                .contractNo(currentContract.getContractNo()) // 기존 계약번호 유지
                .insurerId(request.getInsurerId()) //보험사 ID
                .productOfferingId(request.getProductOfferingId()) //제품 ID
                .contractDate(request.getContractDate()) //계약 일자
                .agentId(request.getAgentId()) //설계사 ID
                .organizationId(request.getOrganizationId()) //조직 ID
                .premiumPerCycleAmount(request.getPremiumPerCycleAmount()) //주기 보험료
                .firstPremiumAmount(request.getFirstPremiumAmount()) // 초회 보험료
                .monthlyEquivalentFirstPremium(monthlyEquivalentFirstPremium) //월납 환산 보험료
                .premiumConversionRuleCode(conversionRuleCode) //납입 주기 변환 코드
                .paymentCycleCode(request.getPaymentCycleCode()) //납입 주기 코드
                .paymentTermMonths(request.getPaymentTermMonths())  //납입 기간
                .standardSurrenderDeductionAmount(
                        request.getStandardSurrenderDeductionAmount()   //해약공제액
                )
                .currentStatus(request.getContractStatus())  //계약 상태
                .dataOrigin(currentContract.getDataOrigin()) // 데이터 출처
                .build();

        int updatedRows =
                contractMapper.updateContract(updatedContract);

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
         * TODO(FUN-030)
         * FUN-036에서 새 스케줄 생성이 끝난 다음
         * capCheckService.calculateAndSave()를 호출한다.
         */

        /*
         * TODO(FUN-026)
         * existingContract.getCurrentStatus()와
         * request.getContractStatus()가 다른 경우
         * 계약상태 사건 이력을 등록한다.
         */

        return ResponseContract.builder()
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
    private void validateInputForUpdate(ContractUpdateRequest request) {
        validateContractDate(request); //계약 일자 적합한지 확인
        validateInsurerAndProduct(request); //보험사 , 보험상품 유무 확인
        validateAgentAndOrganization(request); // 직원 , 조직 유무 확인
    }
}
