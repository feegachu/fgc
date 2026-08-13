package com.susukkang.fgc.reconciliation.mapper;

import com.susukkang.fgc.reconciliation.dto.ReconciliationMatchInsertRow;
import com.susukkang.fgc.reconciliation.dto.ReconciliationResultInsertRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 설명 : 대사 결과와 예상·실제 원천 연결을 멱등 저장하는 Mapper
 *
 * @author hjKang
 * @since 2026-08-14
 * @version 1.2
 */
@Mapper
public interface ReconciliationResultMapper {

    int insertResult(ReconciliationResultInsertRow row);

    Long findResultId(
            @Param("reconciliationRunId") Long reconciliationRunId,
            @Param("matchGroupKey") String matchGroupKey
    );

    int insertMatch(ReconciliationMatchInsertRow row);

    long countByRunId(@Param("reconciliationRunId") Long reconciliationRunId);
}
