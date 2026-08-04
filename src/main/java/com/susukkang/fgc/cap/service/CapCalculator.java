package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.dto.CapCalculationResult;

/**
 * FGC-FUN-030 초년도 모집수수료 한도 계산 엔진
 *
 * 계약 1건·지급단계 1개에 대해 초년도(계약월차 1~12) 1,200% 한도와 산입액을 계산
 *
 * schedule_line은 분급 체계와 무관하게 같은 컬럼 구조(계약월차·예상금액)를 쓰고,
 * cap_rule_set은 계약일 범위로 조회하므로 연도별로 분기X
 */
public interface CapCalculator {

    CapCalculationResult calculate(CapCalculationCommand command);
}
