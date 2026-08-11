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
}
