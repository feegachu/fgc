package com.susukkang.fgc.cap.mapper;

import com.susukkang.fgc.cap.dto.RefundRateTableView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDate;

@Mapper
public interface RefundRateMapper {

    /**
     * (보험사·상품·납입기간·채널) 조합으로 기준일에 유효한 예상 해약환급률표 헤더를 조회
     * 판매버전(product_offering)으로 조인X
     * refund_rate_table 의 범위는 uq_refund_table_scope(insurer_id, product_id, payment_term_months, channel_code, effective_from)
     */
    RefundRateTableView findApplicableTable(@Param("insurerId") Long insurerId,
                                             @Param("productId") Long productId,
                                             @Param("paymentTermMonths") Integer paymentTermMonths,
                                             @Param("channelCode") String channelCode,
                                             @Param("asOfDate") LocalDate asOfDate);

    /** 표 안의 특정 차월(contract_month_no) 예상 해약환급률(%) */
    BigDecimal findRateAtMonth(@Param("refundRateTableId") Long refundRateTableId,
                               @Param("contractMonthNo") int contractMonthNo);
}
