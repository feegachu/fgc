package com.susukkang.fgc.reconciliation.mapper;

import com.susukkang.fgc.reconciliation.dto.InsurerGaActualSourceRow;
import com.susukkang.fgc.reconciliation.dto.InsurerGaExpectedSourceRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 설명 : 기존 정규화 DB에서 보험사→GA 대사 원자행을 조회하는 Mapper
 *
 * @author yslee
 * @since 2026-08-12
 * @version 1.2
 */
@Mapper
public interface InsurerGaReconciliationMapper {

    List<InsurerGaExpectedSourceRow> findExpectedSources(
            @Param("settlementMonth") LocalDate settlementMonth,
            @Param("insurerId") Long insurerId
    );

    List<InsurerGaActualSourceRow> findActualSources(
            @Param("settlementMonth") LocalDate settlementMonth,
            @Param("insurerId") Long insurerId
    );
}
