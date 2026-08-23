package com.susukkang.fgc.contract.dto;

import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.contract.code.ContractStatus;
import com.susukkang.fgc.contract.code.DataOrigin;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
/**
 * 설명 : 조회용 ContractListDTO
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-05
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractView {
    private Long contractId; //계약 id , 표시는 X
    private String contractNo; //계약번호
    private String insurerName; //회사명
    private String productName; // 상품명
    private LocalDate contractDate; //계약 일
    private BigDecimal monthlyEquivalentFirstPremium; //월납환산 초회 보험료
    private String agentIdName; //"FC" + AgentId + 모집 설계사
    private ContractStatus contractStatus; //계약 상태
    private CapResultStatus capResultStatus; //1200% 판정
    private DataOrigin dataOrigin; //데이터 출처
}
