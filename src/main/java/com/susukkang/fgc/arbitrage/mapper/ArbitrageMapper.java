package com.susukkang.fgc.arbitrage.mapper;

import com.susukkang.fgc.arbitrage.dto.ArbitrageCheckSearchCondition;
import com.susukkang.fgc.arbitrage.dto.ArbitrageCheckSummary;
import com.susukkang.fgc.arbitrage.dto.ArbitrageCheckView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 설명 : 차익거래 검증 결과를 가지고 CRUD 작업을 하는 Mapper
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
@Mapper
public interface ArbitrageMapper {
    //검색조건에 해당하는 차익거래 검증 결과 List를 select한다
    List<ArbitrageCheckView> selectByCondition(
            @Param("condition") ArbitrageCheckSearchCondition condition,
            @Param("offset") int offset,
            @Param("size") int size);
    //검색조건에 해당하는 차익거래 검증 결과의 개수를 select한다
    ArbitrageCheckSummary arbitrageCheckSummary(
            @Param("condition") ArbitrageCheckSearchCondition condition);
}
