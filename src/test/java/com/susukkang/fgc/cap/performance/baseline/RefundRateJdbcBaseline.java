package com.susukkang.fgc.cap.performance.baseline;

import com.susukkang.fgc.cap.dto.RefundRateTableView;
import java.math.BigDecimal;
import java.time.LocalDate;
import com.susukkang.fgc.common.persistence.baseline.JdbcBaseline;
import javax.sql.DataSource;
import java.util.HashMap;

/** 전환 전 SQL을 실행하는 테스트 전용 JDBC 기준 구현. */
public class RefundRateJdbcBaseline {
    private final JdbcBaseline jdbc;
    public RefundRateJdbcBaseline(DataSource dataSource) { this.jdbc = new JdbcBaseline(dataSource); }

    public RefundRateTableView findApplicableTable(Long insurerId, Long productId, Integer paymentTermMonths, String channelCode, LocalDate asOfDate) {
        var parameters = new HashMap<String, Object>();
        parameters.put("insurerId", insurerId);
        parameters.put("productId", productId);
        parameters.put("paymentTermMonths", paymentTermMonths);
        parameters.put("channelCode", channelCode);
        parameters.put("asOfDate", asOfDate);
        return jdbc.selectOne("com.susukkang.fgc.cap.performance.baseline.RefundRateJdbcBaseline.findApplicableTable", parameters);
    }
    public BigDecimal findRateAtMonth(Long refundRateTableId, int contractMonthNo) {
        var parameters = new HashMap<String, Object>();
        parameters.put("refundRateTableId", refundRateTableId);
        parameters.put("contractMonthNo", contractMonthNo);
        return jdbc.selectOne("com.susukkang.fgc.cap.performance.baseline.RefundRateJdbcBaseline.findRateAtMonth", parameters);
    }
}
