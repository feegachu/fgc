package com.susukkang.fgc.cap.mapper;

import com.susukkang.fgc.cap.dto.CapContractView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CapContractMapper {

    /** 계약 + 판매버전 + 상품을 조인해 cap 계산에 필요한 값만 투영해 조회 */
    CapContractView findById(@Param("contractId") Long contractId);
}
