package com.susukkang.fgc.contract.mapper;

import com.susukkang.fgc.contract.dto.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;
/**
 * 설명 : ContractMapper
 * tb_insurance_contract  계약 상태사건을 CRUD하는 Mapper
 * @author hjKang
 * @version 1.0
 * @since 2026-08-05
 */
@Mapper
public interface ContractMapper {
    // 검색조건에 따른 계약 조회
    List<ContractView> selectByCondition(ContractSearchCondition condition);
    // 계약Id에 따른 계약 조회
    InsuranceContract selectById(Long id);
    // 계약Id에 따른 계약 및 상품 정보 조회
    ContractDetailResponse selectContractDetailById(Long id);

    boolean existsInsurer(Long insurerId);

    boolean existsProductOffering(
            @Param("insurerId") Long insurerId,
            @Param("productOfferingId") Long productOfferingId,
            @Param("contractDate") LocalDate contractDate
    );

    boolean existsAgent(
            @Param("agentId") Long agentId,
            @Param("contractDate") LocalDate contractDate
    );

    boolean existsAgentOrganization(
            @Param("agentId") Long agentId,
            @Param("organizationId") Long organizationId,
            @Param("contractDate") LocalDate contractDate
    );

    boolean existsContractNo(
            @Param("insurerId") Long insurerId,
            @Param("contractNo") String contractNo
    );

    int insertContract(InsuranceContract insuranceContract);

    int updateContract(InsuranceContract updatedContract);
}
