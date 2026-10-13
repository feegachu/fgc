package com.susukkang.fgc.cap.performance.baseline;

import com.susukkang.fgc.cap.dto.CapIncludedAmountSummary;
import com.susukkang.fgc.common.code.PaymentStage;
import java.util.List;
import com.susukkang.fgc.common.persistence.baseline.JdbcBaseline;
import javax.sql.DataSource;
import java.util.HashMap;

/** 전환 전 SQL을 실행하는 테스트 전용 JDBC 기준 구현. */
public class CapIncludedAmountJdbcBaseline {
    private final JdbcBaseline jdbc;
    public CapIncludedAmountJdbcBaseline(DataSource dataSource) { this.jdbc = new JdbcBaseline(dataSource); }

    public List<CapIncludedAmountSummary> sumIncludedAmountByContractAndAgent(Long contractId, Long transactionId, PaymentStage paymentStage) {
        var parameters = new HashMap<String, Object>();
        parameters.put("contractId", contractId);
        parameters.put("transactionId", transactionId);
        parameters.put("paymentStage", paymentStage);
        return jdbc.selectList("com.susukkang.fgc.cap.performance.baseline.CapIncludedAmountJdbcBaseline.sumIncludedAmountByContractAndAgent", parameters);
    }
    public List<CapIncludedAmountSummary> sumConfirmedIncludedAmountByContractAndAgent(Long contractId, PaymentStage paymentStage) {
        var parameters = new HashMap<String, Object>();
        parameters.put("contractId", contractId);
        parameters.put("paymentStage", paymentStage);
        return jdbc.selectList("com.susukkang.fgc.cap.performance.baseline.CapIncludedAmountJdbcBaseline.sumConfirmedIncludedAmountByContractAndAgent", parameters);
    }
}
