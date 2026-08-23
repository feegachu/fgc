package com.susukkang.fgc.contract.mapper;

import com.susukkang.fgc.contract.dto.ContractStatusEventProcessingRow;
import com.susukkang.fgc.contract.dto.ContractStatusEventRow;
import com.susukkang.fgc.contract.code.ContractStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.time.OffsetDateTime;

/**
 * 설명 : 계약 상태 사건 Mapper
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-05
 */
@Mapper
public interface ContractStatusEventMapper {

    int insertInitialEvent(
            @Param("contractId") Long contractId,
            @Param("newStatus") ContractStatus newStatus,
            @Param("effectiveAt") OffsetDateTime effectiveAt,
            @Param("receivedAt") OffsetDateTime receivedAt
    );

    List<ContractStatusEventRow> selectByContractId(@Param("contractId") Long contractId);

    List<ContractStatusEventProcessingRow> selectProcessingsByContractId(
            @Param("contractId") Long contractId
    );
}
