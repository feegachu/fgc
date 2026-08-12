package com.susukkang.fgc.contract.mapper;

import com.susukkang.fgc.contract.dto.ContractStatusEventProcessingRow;
import com.susukkang.fgc.contract.dto.ContractStatusEventRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 설명 : 계약 상태 사건 Mapper
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-05
 */
@Mapper
public interface ContractStatusEventMapper {

    List<ContractStatusEventRow> selectByContractId(@Param("contractId") Long contractId);

    List<ContractStatusEventProcessingRow> selectProcessingsByContractId(
            @Param("contractId") Long contractId
    );
}
