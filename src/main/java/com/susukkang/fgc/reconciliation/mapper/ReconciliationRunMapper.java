package com.susukkang.fgc.reconciliation.mapper;

import com.susukkang.fgc.reconciliation.dto.ReconciliationRunInsertRow;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunRow;
import com.susukkang.fgc.common.code.PaymentStage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 설명 : 대사 실행 생성 및 상태 전이 Mapper
 *
 * @author yslee
 * @since 2026-08-12
 * @version 1.2
 */
@Mapper
public interface ReconciliationRunMapper {

    boolean existsActiveInsurer(@Param("insurerId") Long insurerId);

    void insert(ReconciliationRunInsertRow row);

    int transitionToRunning(@Param("reconciliationRunId") Long reconciliationRunId);

    int transitionToCompleted(@Param("reconciliationRunId") Long reconciliationRunId);

    int transitionToFailed(@Param("reconciliationRunId") Long reconciliationRunId);

    ReconciliationRunRow findById(@Param("reconciliationRunId") Long reconciliationRunId);

    ReconciliationRunRow findByNaturalKey(
            @Param("settlementMonth") LocalDate settlementMonth,
            @Param("paymentStage") PaymentStage paymentStage,
            @Param("insurerId") Long insurerId,
            @Param("validationRunId") Long validationRunId
    );

    List<Long> findSelectedInsurerIds(@Param("validationRunId") Long validationRunId);
}
