package com.susukkang.fgc.validation.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * fgc.exception_case 쓰기 전용 매퍼
 * DailyChangedContractJob이 계약 단위 데이터 품질 오류를 만났을 때 DATA_QUALITY 예외를 여기로 남김
 */
@Mapper
public interface ExceptionCaseMapper {

    /**
     * exception_key = "DATA_QUALITY:{validationRunId}:INSURANCE_CONTRACT:{contractId}"
     * 같은 실행 안에서 같은 계약이 또 실패해도 ON CONFLICT DO NOTHING으로 중복 행이 안 생김(재시작해도 exception_case는 1건).
     *
     * @return 실제로 새로 INSERT됐으면 1, 이미 같은 key로 있었으면(ON CONFLICT) 0
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
     * @return 처리된 건수
     * @author hjKang
     * @since 2026-08-15
     */
    // Cap 1200%관련
    long insertFromCapChecks(@Param("validationRunId") Long validationRunId);
    // 차익거래 관련
    long insertFromArbitrageChecks(@Param("validationRunId") Long validationRunId);
    // 대사 일치 관련
    long insertFromReconciliationResults(@Param("validationRunId") Long validationRunId);
}
