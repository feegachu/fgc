package com.susukkang.fgc.validation.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * SRC-032 예외 업무건과 실행별 검출 이력을 함께 기록하는 쓰기 매퍼.
 * 실제 동시성·멱등성은 DB 공용 함수 record_exception_detection이 한 곳에서 보장한다.
 */
@Mapper
public interface ExceptionCaseMapper {

    /**
     * 같은 검증월·계약·원인의 업무건은 하나이며 실행별 occurrence만 추가된다.
     *
     * @return 이 실행의 occurrence가 새로 기록됐으면 1, 같은 실행 재시도면 0
     */
    int insertDataQualityCase(@Param("validationRunId") Long validationRunId,
                               @Param("contractId") Long contractId,
                               @Param("title") String title,
                               @Param("description") String description);

    /** 월 검증의 계약별 1,200% 계산 실패를 지급 단계별로 멱등 기록한다. */
    int insertCapCheckFailure(
            @Param("validationRunId") Long validationRunId,
            @Param("contractId") Long contractId,
            @Param("paymentStage") String paymentStage,
            @Param("description") String description);

    /** 차익거래 후보 결과를 원천으로 중복 없는 검토 예외를 생성한다. */
    int insertArbitrageCandidate(
            @Param("validationRunId") Long validationRunId,
            @Param("contractId") Long contractId,
            @Param("arbitrageCheckId") Long arbitrageCheckId,
            @Param("paymentStage") String paymentStage,
            @Param("description") String description);

    /** 차익거래 검증의 자료 부족·정합성 오류를 검토 예외로 생성한다. */
    int insertArbitrageReviewCase(
            @Param("exceptionType") String exceptionType,
            @Param("validationRunId") Long validationRunId,
            @Param("contractId") Long contractId,
            @Param("arbitrageCheckId") Long arbitrageCheckId,
            @Param("paymentStage") String paymentStage,
            @Param("title") String title,
            @Param("description") String description);

    /**
     * 설명 : 예외건 처리를 위해 만든 메서드들
     *
     * @param  validationRunId 배치 실행 ID
     * @return 이 실행에 새로 기록된 occurrence 건수
     * @author hjKang
     * @since 2026-08-15
     */
    // Cap 1200%관련
    long insertFromCapChecks(@Param("validationRunId") Long validationRunId);
    // 차익거래 관련
    long insertFromArbitrageChecks(@Param("validationRunId") Long validationRunId);
    // 대사 일치 관련
    long insertFromReconciliationResults(@Param("validationRunId") Long validationRunId);
    // 검증원장 차변·대변 불균형 관련
    long insertFromJournalImbalances(@Param("validationRunId") Long validationRunId);
}
