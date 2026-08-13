package com.susukkang.fgc.contract.mapper;

import com.susukkang.fgc.contract.dto.ContractTransactionAttributionRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ContractTransactionProjectionMapper {
    List<ContractTransactionAttributionRow> findTransactionAttributionsByContractId(
            @Param("contractId") Long contractId);
}
