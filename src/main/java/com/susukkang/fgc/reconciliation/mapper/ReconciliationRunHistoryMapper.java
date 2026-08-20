package com.susukkang.fgc.reconciliation.mapper;

import com.susukkang.fgc.reconciliation.dto.ReconciliationRunHistoryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * IF-API-39 대사 실행 이력 조회 전용 Mapper(RECO-W01 "④ 실행 이력"). 생성·상태전이는
 * ReconciliationRunMapper 몫이라 여기서는 조회만 다룬다.
 */
@Mapper
public interface ReconciliationRunHistoryMapper {

    /**
     * settlementMonth/paymentStage/insurerId가 null이면 그 조건은 걸지 않는다(전체).
     * sortDirection은 "asc"/"desc"만 유효하고(그 외 값은 desc로 취급), created_at 기준
     * 정렬 후 reconciliation_run_id로 동률을 깬다 — 같은 정산월·지급단계라도 월 검증
     * 실행/보험회사가 다르면 uq_reconciliation_run(V1:1444-1445) 덕분에 서로 다른 행으로
     * 자연히 분리돼 나온다.
     */
    List<ReconciliationRunHistoryRow> search(@Param("settlementMonth") LocalDate settlementMonth,
                                              @Param("paymentStage") String paymentStage,
                                              @Param("insurerId") Long insurerId,
                                              @Param("sortDirection") String sortDirection,
                                              @Param("offset") int offset,
                                              @Param("limit") int limit);

    /** search와 같은 조건(settlementMonth/paymentStage/insurerId)의 전체 건수 — 페이징 totalElements 계산용. */
    long count(@Param("settlementMonth") LocalDate settlementMonth,
               @Param("paymentStage") String paymentStage,
               @Param("insurerId") Long insurerId);
}
