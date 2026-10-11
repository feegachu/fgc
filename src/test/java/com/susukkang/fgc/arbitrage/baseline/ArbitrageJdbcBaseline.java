package com.susukkang.fgc.arbitrage.baseline;

import com.susukkang.fgc.arbitrage.dto.*;
import com.susukkang.fgc.common.code.PaymentStage;
import java.util.List;
import java.math.BigDecimal;
import java.time.LocalDate;
import com.susukkang.fgc.common.persistence.baseline.JdbcBaseline;
import javax.sql.DataSource;
import java.util.HashMap;

/** 전환 전 SQL을 실행하는 테스트 전용 JDBC 기준 구현. */
public class ArbitrageJdbcBaseline {
    private final JdbcBaseline jdbc;
    public ArbitrageJdbcBaseline(DataSource dataSource) { this.jdbc = new JdbcBaseline(dataSource); }

    public List<ArbitrageCheckView> selectByCondition(ArbitrageCheckSearchCondition condition, int offset, int size) {
        var parameters = new HashMap<String, Object>();
        parameters.put("condition", condition);
        parameters.put("offset", offset);
        parameters.put("size", size);
        return jdbc.selectList("com.susukkang.fgc.arbitrage.baseline.ArbitrageJdbcBaseline.selectByCondition", parameters);
    }
    public ArbitrageCheckSummary arbitrageCheckSummary(ArbitrageCheckSearchCondition condition) {
        var parameters = new HashMap<String, Object>();
        parameters.put("condition", condition);
        return jdbc.selectOne("com.susukkang.fgc.arbitrage.baseline.ArbitrageJdbcBaseline.arbitrageCheckSummary", parameters);
    }
    public long countByCondition(ArbitrageCheckSearchCondition condition) {
        var parameters = new HashMap<String, Object>();
        parameters.put("condition", condition);
        return jdbc.selectOne("com.susukkang.fgc.arbitrage.baseline.ArbitrageJdbcBaseline.countByCondition", parameters);
    }
    public ArbitrageCalculationSource selectCalculationSource(Long contractId, LocalDate asOfDate) {
        var parameters = new HashMap<String, Object>();
        parameters.put("contractId", contractId);
        parameters.put("asOfDate", asOfDate);
        return jdbc.selectOne("com.susukkang.fgc.arbitrage.baseline.ArbitrageJdbcBaseline.selectCalculationSource", parameters);
    }
    public ConfirmedCommissionSummary sumConfirmedCommissionAmount(Long contractId, PaymentStage paymentStage, LocalDate asOfDate) {
        var parameters = new HashMap<String, Object>();
        parameters.put("contractId", contractId);
        parameters.put("paymentStage", paymentStage);
        parameters.put("asOfDate", asOfDate);
        return jdbc.selectOne("com.susukkang.fgc.arbitrage.baseline.ArbitrageJdbcBaseline.sumConfirmedCommissionAmount", parameters);
    }
    public BigDecimal sumPlannedCommissionAmount(Long contractId, PaymentStage paymentStage) {
        var parameters = new HashMap<String, Object>();
        parameters.put("contractId", contractId);
        parameters.put("paymentStage", paymentStage);
        return jdbc.selectOne("com.susukkang.fgc.arbitrage.baseline.ArbitrageJdbcBaseline.sumPlannedCommissionAmount", parameters);
    }
    public List<ArbitrageRefundRateCandidate> selectRefundRateCandidates(ArbitrageCalculationSource source, LocalDate asOfDate) {
        var parameters = new HashMap<String, Object>();
        parameters.put("source", source);
        parameters.put("asOfDate", asOfDate);
        return jdbc.selectList("com.susukkang.fgc.arbitrage.baseline.ArbitrageJdbcBaseline.selectRefundRateCandidates", parameters);
    }
    public int insertArbitrageCheck(ArbitrageCheckInsertDTO row) {
        return jdbc.update("com.susukkang.fgc.arbitrage.baseline.ArbitrageJdbcBaseline.insertArbitrageCheck", row);
    }
    public List<ArbitrageCheckView> selectByContractId(Long contractId, PaymentStage paymentStage) {
        var parameters = new HashMap<String, Object>();
        parameters.put("contractId", contractId);
        parameters.put("paymentStage", paymentStage);
        return jdbc.selectList("com.susukkang.fgc.arbitrage.baseline.ArbitrageJdbcBaseline.selectByContractId", parameters);
    }
    public List<Long> selectSelectedContractIds(Long validationRunId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("validationRunId", validationRunId);
        return jdbc.selectList("com.susukkang.fgc.arbitrage.baseline.ArbitrageJdbcBaseline.selectSelectedContractIds", parameters);
    }
}
