package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.cap.dto.RefundRateQuery;
import com.susukkang.fgc.cap.dto.RefundRateResolution;

import java.util.Optional;

/**
 * 상품코드·납입기간·채널·기준일에 맞는 예상 해약환급률표를 찾아 12차월 예상 해약환급률과
 * 그 판정에 사용한 표 버전(refund_rate_table_id, policy_version_id/version_no) return (REG-08, REG-23)
 *
 * 표나 12차월 값이 없으면 억지로 추정하지 않고 empty return — 호출자가 REVIEW_REQUIRED 로 처리해야함
 */
public interface ProductRefundRateResolver {

    Optional<RefundRateResolution> resolve(RefundRateQuery query);
}
