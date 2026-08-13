package com.susukkang.fgc.validation.mapper;

import com.susukkang.fgc.contract.dto.InsuranceContract;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 설명 : 월 배치 정산에서 스케줄 상태를 검사할 때 사용하는 Mapper
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-13
 */
@Mapper
public interface ValidationScheduleMapper {
    // 배치 validationRunId에 해당하는 계약을 가져온다
    List<InsuranceContract> selectContractsByValidationRunId(Long validationRunId);
}
