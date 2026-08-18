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
     * settlementMonth/paymentStage가 null이면 그 조건은 걸지 않는다(전체).
     * created_at 내림차순(최신 실행·재실행이 먼저) — 같은 정산월·지급단계라도 월 검증
     * 실행/보험회사가 다르면 uq_reconciliation_run(V1:1444-1445) 덕분에 서로 다른 행으로
     * 자연히 분리돼 나온다.
     */
    List<ReconciliationRunHistoryRow> search(@Param("settlementMonth") LocalDate settlementMonth,
                                              @Param("paymentStage") String paymentStage);
}
