package com.susukkang.fgc.cap.performance.baseline;

import com.susukkang.fgc.cap.dto.ScheduleAmountView;
import java.util.List;
import com.susukkang.fgc.common.persistence.baseline.JdbcBaseline;
import javax.sql.DataSource;
import java.util.HashMap;

/** 전환 전 SQL을 실행하는 테스트 전용 JDBC 기준 구현. */
public class CapScheduleAmountJdbcBaseline {
    private final JdbcBaseline jdbc;
    public CapScheduleAmountJdbcBaseline(DataSource dataSource) { this.jdbc = new JdbcBaseline(dataSource); }

    public List<ScheduleAmountView> findFirstYearScheduleAmounts(Long contractId, String paymentStage, int firstYearMonths) {
        var parameters = new HashMap<String, Object>();
        parameters.put("contractId", contractId);
        parameters.put("paymentStage", paymentStage);
        parameters.put("firstYearMonths", firstYearMonths);
        return jdbc.selectList("com.susukkang.fgc.cap.performance.baseline.CapScheduleAmountJdbcBaseline.findFirstYearScheduleAmounts", parameters);
    }
}
