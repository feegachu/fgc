package com.susukkang.fgc.validation.mapper;

import com.susukkang.fgc.reconciliation.dto.ReconciliationExceptionBulkCreateRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * fgc.exception_case / exception_occurrence 쓰기 전용 매퍼.
 *
 * V23_1(SRC-032) 이후 모든 메서드가 raw INSERT 대신 fgc.record_exception_detection()을
 * 호출한다 — 안정 업무키(exception_type:검증월:CONTRACT:...)로 같은 이슈의 재검출을
 * 판단해, 새 업무건이면 만들고 이미 있으면 실행별 검출 이력(exception_occurrence)만
 * 남기면서 RESOLVED였던 건은 자동으로 다시 연다. 반환값은 새로 생성된 "업무건"
 * 개수다(재검출로 기존 건에 이력만 추가된 건 안 세다) — 그래서 반복 호출해도
 * ValidationRunExceptionServiceImpl의 누적 카운트가 계속 커지지 않는다.
 */
@Mapper
public interface ExceptionCaseMapper {

    /** 일일 변경 계약 재검증 실패를 검증월 기준 안정 업무키로 기록한다. */
    Long insertDataQualityCase(@Param("validationRunId") Long validationRunId,
                                @Param("contractId") Long contractId,
                                @Param("title") String title,
                                @Param("description") String description);

    /** 월 검증의 계약별 1,200% 계산 실패를 지급 단계별로 검증월 기준 안정 업무키로 기록한다. */
    Long insertCapCheckFailure(
            @Param("validationRunId") Long validationRunId,
            @Param("contractId") Long contractId,
            @Param("paymentStage") String paymentStage,
            @Param("description") String description);

    /** 차익거래 후보 결과를 원천으로 검증월 기준 안정 업무키의 검토 예외를 기록한다. */
    Long insertArbitrageCandidate(
            @Param("validationRunId") Long validationRunId,
            @Param("contractId") Long contractId,
            @Param("arbitrageCheckId") Long arbitrageCheckId,
            @Param("paymentStage") String paymentStage,
            @Param("description") String description);

    /** 차익거래 검증의 자료 부족·정합성 오류를 검증월 기준 안정 업무키의 검토 예외로 기록한다. */
    Long insertArbitrageReviewCase(
            @Param("exceptionType") String exceptionType,
            @Param("validationRunId") Long validationRunId,
            @Param("contractId") Long contractId,
            @Param("arbitrageCheckId") Long arbitrageCheckId,
            @Param("paymentStage") String paymentStage,
            @Param("title") String title,
            @Param("description") String description);

    /**
     * @param  validationRunId 배치 실행 ID
     * @return 새로 생성된 업무건 수(재검출로 기존 건에 이력만 추가된 건 제외)
     */
    long insertFromCapChecks(@Param("validationRunId") Long validationRunId);

    long insertFromArbitrageChecks(@Param("validationRunId") Long validationRunId);

    long insertFromReconciliationResults(@Param("validationRunId") Long validationRunId);

    long insertFromJournalImbalances(@Param("validationRunId") Long validationRunId);

    /**
     * IF-API-42 — RECO-W01 "불일치 예외 일괄 생성" 버튼(수동, reconciliationRunId 기준).
     * insertFromReconciliationResults와 안정 업무키 형식이 완전히 같다(교차 재검출 판단이
     * 핵심이라 문자 그대로 맞춰야 한다) — 다만 필터를 validation_run_id가 아니라
     * reconciliation_run_id로 건다.
     *
     * fgc.record_exception_detection()은 validation_run_id가 반드시 있어야 하므로,
     * 이 대사 실행이 월 검증 실행과 연결돼 있지 않으면(reconciliation_run.validation_run_id
     * IS NULL) 호출 전에 서비스 레이어에서 걸러야 한다(RECO_003).
     *
     * 후보 집합을 한 번만 읽어서 그 스냅샷 그대로 처리하고, 후보 건수와 새로 생성된
     * 업무건 수를 같은 문장에서 함께 센다.
     */
    ReconciliationExceptionBulkCreateRow bulkCreateFromReconciliationResultsByRun(
            @Param("reconciliationRunId") Long reconciliationRunId);
}
