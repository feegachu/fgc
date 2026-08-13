package com.susukkang.fgc.contract.mapper;

import com.susukkang.fgc.contract.dto.ContractJournalLineRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ContractJournalProjectionMapper {
    List<ContractJournalLineRow> findJournalLinesByContractId(@Param("contractId") Long contractId);
}
