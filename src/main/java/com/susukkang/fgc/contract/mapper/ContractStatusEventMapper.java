package com.susukkang.fgc.contract.mapper;

import com.susukkang.fgc.contract.dto.ContractStatusEventInsertRow;
import com.susukkang.fgc.contract.dto.ContractStatusEventResponse;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
/**
 * 설명 : ContractStatusMapper
 * tb_contract_status_event 계약 상태사건을 CRUD하는 Mapper
 * @author hjKang
 * @version 1.0
 * @since 2026-08-05
 */
public interface ContractStatusEventMapper {
    /**
     * 설명 : 계약 ID를 조회해서 계약 상태 List를 가져온다
     *
     * @param  id 계약 ID
     * @return List<ContractStatusEvent> 계약 상태 List
     * @author hjKang
     * @since 2026-08-05
     */
    List<ContractStatusEventResponse> selectByContractId(Long contractId);
}
