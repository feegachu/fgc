package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.dto.CapCalculationResult;

/**
 * FGC-FUN-030 초년도 모집수수료 한도 계산 엔진
 *
 * 계약 1건·지급단계 1개에 대해 초년도(계약월차 1~12) 1,200% 한도와 산입액을 "계산만" 한다.
 * cap_check/cap_check_detail 저장은 이 인터페이스의 책임이 아니다 — CapCheckService 같은
 * 오케스트레이션 계층이 calculate() 결과를 받아 저장과 트랜잭션 경계를 관리한다(#3).
 * 계산과 저장을 분리해 둔 덕분에 실시간 API(#3)와 월 배치(#4)가 서로 다른 저장 방식
 * (단건 즉시 저장 vs 청크 단위 배치 저장)을 각자 선택할 수 있다.
 *
 * schedule_line은 분급 체계와 무관하게 같은 컬럼 구조(계약월차·예상금액)를 쓰고,
 * cap_rule_set은 계약일 범위로 조회하므로 연도별로 분기X
 *
 * ★ 알려진 한계: "2026-07-01 이후 체결된 월납 보장성보험" 이라는 1차 데모 적용대상 판정은
 *   이 계산 엔진이 하지 않는다. cap_rule_set 에는 payment_cycle_code/protection_type 컬럼이
 *   없어 판정 근거가 룰셋 데이터에 없고, 대상 선정은 validation_target(selection_status)
 *   같은 별도 계층의 책임으로 남겨두기로 결정했다. 즉 이 메서드는 호출된 계약을 적용대상
 *   여부와 무관하게 계산한다 — 호출자가 대상 여부를 먼저 걸러야 한다.
 */
public interface CapCalculator {

    CapCalculationResult calculate(CapCalculationCommand command);
}
