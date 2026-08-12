package com.susukkang.fgc.policy.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * POL-W01 예상 해약환급률표 탭 1행. refund_rate_table + insurer/product 이름 + 차월 라인 중첩 투영.
 * product_offering_id 는 대표 예시일 뿐이므로 조인·표시에 쓰지 않는다(쿡북 주의사항).
 */
@Getter
@Setter
public class RefundRateTableRow {
    private Long refundRateTableId;
    private String insurerName;
    private String productName;
    private Integer paymentTermMonths;
    private String channelCode;
    private BigDecimal averageDeclaredRatePct;
    private Boolean standardDeduction80Yn;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private String sourceProductCode;
    private String sourceDocumentRef;
    private List<RefundRateLineRow> lines;
}
