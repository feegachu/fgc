package com.susukkang.fgc.contract.dto;

import com.susukkang.fgc.common.code.ContractStatus;
import com.susukkang.fgc.common.code.ContractStatusReasonCode;
import com.susukkang.fgc.common.code.DataOrigin;
import com.susukkang.fgc.common.code.PaymentCycleCode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * 설명 : ResponseContractDetail
 * 계약 상세보기 결과 응답 DTO
 * @author hjKang
 * @version 1.0
 * @since 2026-08-05
 */
@Getter
@Setter
@Builder
@RequiredArgsConstructor
@AllArgsConstructor
public class ContractDetailResponse {
    //기본 정보 탭
    private Long contractId; //계약 ID
    private Long insurerId; //보험회사 ID
    private String insurerName; //보험회사명
    private Long productOfferingId; //상품 판매버전 ID
    private String contractNo; //계약번호
    private String productName; //상품명
    private String offeringVersion; //상품 판매버전
    private LocalDate contractDate; //계약일
    private Long agentId; //모집 설계사 ID
    private String agentName; //모집 설계사명
    private Long organizationId; //소속 조직 ID
    private String organizationName; //소속 조직명
    private BigDecimal premiumPerCycleAmount; //주기 보험료
    private PaymentCycleCode paymentCycleCode; //납입주기
    private BigDecimal firstPremiumAmount; //초회 보험료
    private BigDecimal monthlyEquivalentFirstPremium; //월납 환산 보험료
    private Integer paymentTermMonths; //납입기간
    private BigDecimal standardSurrenderDeductionAmount; //표준해약공제액
    private ContractStatus contractStatus; //계약상태
    private DataOrigin dataOrigin; //계약 출처
    private OffsetDateTime updatedAt; // 수정 요청의 expectedUpdatedAt으로 원문 그대로 전달

    /** SIR-008: 조회 화면은 서버 enum의 한글 라벨을 우선 사용한다. */
    public String getContractStatusLabel() {
        return contractStatus == null ? null : contractStatus.label();
    }

    public String getPaymentCycleCodeLabel() {
        return paymentCycleCode == null ? null : paymentCycleCode.label();
    }

    public String getDataOriginLabel() {
        return dataOrigin == null ? null : dataOrigin.label();
    }

    /** CONT-W03 변경 상태 선택지. 서버 전이 검사와 같은 표(ContractStatus#nextStatuses)다. */
    public Set<ContractStatus> getAllowedNextStatuses() {
        return contractStatus == null ? Set.of() : contractStatus.nextStatuses();
    }

    /** CONT-W03 상태 변경 사유코드 선택지. 서버가 받는 허용목록과 같다. */
    public List<ContractStatusReasonCode> getStatusReasonCodes() {
        return List.of(ContractStatusReasonCode.values());
    }

    //계약상태 사건 이력 List 2차
    //private List<ContractStatusEventResponse> contractStatusEventResponses;
}
