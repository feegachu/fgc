package com.susukkang.fgc.cap.mapper;

import com.susukkang.fgc.cap.dto.ScheduleAmountView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CapScheduleAmountMapper {

    /**
     * 계약·지급단계의 현재 운영 예상 스케줄 중 계약월차 1~firstYearMonths에 속하는 줄만 조회
     * schedule_regime 구분X
     * 컬럼 구조가 같으므로 어느 분급 체계든 같은 질의로 초년도 산입 후보를 얻을 수 있음
     */
    List<ScheduleAmountView> findFirstYearScheduleAmounts(@Param("contractId") Long contractId,
                                                            @Param("paymentStage") String paymentStage,
                                                            @Param("firstYearMonths") int firstYearMonths);
}
