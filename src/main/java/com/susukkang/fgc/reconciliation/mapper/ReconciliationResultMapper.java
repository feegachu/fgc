package com.susukkang.fgc.reconciliation.mapper;

import com.susukkang.fgc.reconciliation.dto.ReconciliationMatchInsertRow;
import com.susukkang.fgc.reconciliation.dto.ReconciliationClassificationContext;
import com.susukkang.fgc.reconciliation.dto.ReconciliationResultInsertRow;
import com.susukkang.fgc.reconciliation.dto.ReconciliationResultDetailRow;
import com.susukkang.fgc.reconciliation.dto.ReconciliationResultListRow;
import com.susukkang.fgc.reconciliation.dto.ReconciliationMatchDetailRow;
import com.susukkang.fgc.reconciliation.dto.ReconciliationSummaryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

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

    ReconciliationClassificationContext findClassificationContext(
            @Param("contractId") Long contractId,
            @Param("expectedAgentId") Long expectedAgentId,
            @Param("actualAgentId") Long actualAgentId,
            @Param("journalHeaderIds") List<Long> journalHeaderIds
    );

    List<ReconciliationResultListRow> findResults(
            @Param("reconciliationRunId") Long reconciliationRunId,
            @Param("resultType") String resultType,
            @Param("sortDirection") String sortDirection,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    long countResults(
            @Param("reconciliationRunId") Long reconciliationRunId,
            @Param("resultType") String resultType
    );

    ReconciliationSummaryRow findSummary(@Param("reconciliationRunId") Long reconciliationRunId);

    ReconciliationResultDetailRow findDetail(@Param("reconciliationResultId") Long reconciliationResultId);

    List<ReconciliationMatchDetailRow> findMatches(
            @Param("reconciliationResultId") Long reconciliationResultId
    );
}
