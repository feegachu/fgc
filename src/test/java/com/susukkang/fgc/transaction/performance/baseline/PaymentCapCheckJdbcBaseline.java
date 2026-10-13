package com.susukkang.fgc.transaction.performance.baseline;

import com.susukkang.fgc.cap.dto.CapAgentSummaryRow;
import com.susukkang.fgc.cap.dto.CapCheckDetailInsertRow;
import com.susukkang.fgc.cap.dto.CapCheckDetailLine;
import com.susukkang.fgc.cap.dto.CapCheckInsertRow;
import com.susukkang.fgc.cap.dto.CapCheckListRow;
import com.susukkang.fgc.cap.dto.CapCheckRow;
import com.susukkang.fgc.cap.dto.CapCheckStatusCount;
import com.susukkang.fgc.cap.dto.CapStageSummaryRow;
import com.susukkang.fgc.common.code.PaymentStage;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import com.susukkang.fgc.common.persistence.baseline.JdbcBaseline;
import javax.sql.DataSource;
import java.util.HashMap;

/** 전환 전 SQL을 실행하는 테스트 전용 JDBC 기준 구현. */
public class PaymentCapCheckJdbcBaseline {
    private final JdbcBaseline jdbc;
    public PaymentCapCheckJdbcBaseline(DataSource dataSource) { this.jdbc = new JdbcBaseline(dataSource); }

    public void insertCapCheck(CapCheckInsertRow row) {
        jdbc.update("com.susukkang.fgc.transaction.performance.baseline.PaymentCapCheckJdbcBaseline.insertCapCheck", row);
    }
    public void insertCapCheckDetails(List<CapCheckDetailInsertRow> details) {
        var parameters = new HashMap<String, Object>();
        parameters.put("details", details);
        jdbc.update("com.susukkang.fgc.transaction.performance.baseline.PaymentCapCheckJdbcBaseline.insertCapCheckDetails", parameters);
    }
    public void pruneCapCheckDetails(Long capCheckId, int maxDetailSeq) {
        var parameters = new HashMap<String, Object>();
        parameters.put("capCheckId", capCheckId);
        parameters.put("maxDetailSeq", maxDetailSeq);
        jdbc.update("com.susukkang.fgc.transaction.performance.baseline.PaymentCapCheckJdbcBaseline.pruneCapCheckDetails", parameters);
    }
    public CapCheckRow findLatestByContractAndStage(Long contractId, String paymentStage) {
        var parameters = new HashMap<String, Object>();
        parameters.put("contractId", contractId);
        parameters.put("paymentStage", paymentStage);
        return jdbc.selectOne("com.susukkang.fgc.transaction.performance.baseline.PaymentCapCheckJdbcBaseline.findLatestByContractAndStage", parameters);
    }
    public List<CapCheckDetailLine> findDetailsByCapCheckId(Long capCheckId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("capCheckId", capCheckId);
        return jdbc.selectList("com.susukkang.fgc.transaction.performance.baseline.PaymentCapCheckJdbcBaseline.findDetailsByCapCheckId", parameters);
    }
    public List<CapCheckListRow> search(LocalDate month, String paymentStage, String resultStatus, Long insurerId, Long organizationId, String contractNo, int offset, int limit) {
        var parameters = new HashMap<String, Object>();
        parameters.put("month", month);
        parameters.put("paymentStage", paymentStage);
        parameters.put("resultStatus", resultStatus);
        parameters.put("insurerId", insurerId);
        parameters.put("organizationId", organizationId);
        parameters.put("contractNo", contractNo);
        parameters.put("offset", offset);
        parameters.put("limit", limit);
        return jdbc.selectList("com.susukkang.fgc.transaction.performance.baseline.PaymentCapCheckJdbcBaseline.search", parameters);
    }
    public long count(LocalDate month, String paymentStage, String resultStatus, Long insurerId, Long organizationId, String contractNo) {
        var parameters = new HashMap<String, Object>();
        parameters.put("month", month);
        parameters.put("paymentStage", paymentStage);
        parameters.put("resultStatus", resultStatus);
        parameters.put("insurerId", insurerId);
        parameters.put("organizationId", organizationId);
        parameters.put("contractNo", contractNo);
        return jdbc.selectOne("com.susukkang.fgc.transaction.performance.baseline.PaymentCapCheckJdbcBaseline.count", parameters);
    }
    public List<CapCheckStatusCount> summarize(LocalDate month, String paymentStage, Long insurerId, Long organizationId, String contractNo) {
        var parameters = new HashMap<String, Object>();
        parameters.put("month", month);
        parameters.put("paymentStage", paymentStage);
        parameters.put("insurerId", insurerId);
        parameters.put("organizationId", organizationId);
        parameters.put("contractNo", contractNo);
        return jdbc.selectList("com.susukkang.fgc.transaction.performance.baseline.PaymentCapCheckJdbcBaseline.summarize", parameters);
    }
    public List<CapStageSummaryRow> summarizeByStage(LocalDate month, Long insurerId, Long organizationId, String contractNo) {
        var parameters = new HashMap<String, Object>();
        parameters.put("month", month);
        parameters.put("insurerId", insurerId);
        parameters.put("organizationId", organizationId);
        parameters.put("contractNo", contractNo);
        return jdbc.selectList("com.susukkang.fgc.transaction.performance.baseline.PaymentCapCheckJdbcBaseline.summarizeByStage", parameters);
    }
    public List<CapAgentSummaryRow> summarizeByAgent(LocalDate month, Long insurerId, Long organizationId, String contractNo) {
        var parameters = new HashMap<String, Object>();
        parameters.put("month", month);
        parameters.put("insurerId", insurerId);
        parameters.put("organizationId", organizationId);
        parameters.put("contractNo", contractNo);
        return jdbc.selectList("com.susukkang.fgc.transaction.performance.baseline.PaymentCapCheckJdbcBaseline.summarizeByAgent", parameters);
    }
    public CapCheckRow findById(Long capCheckId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("capCheckId", capCheckId);
        return jdbc.selectOne("com.susukkang.fgc.transaction.performance.baseline.PaymentCapCheckJdbcBaseline.findById", parameters);
    }
    public BigDecimal selectComplianceEvidenceAmount(Long contractId, PaymentStage paymentStage) {
        var parameters = new HashMap<String, Object>();
        parameters.put("contractId", contractId);
        parameters.put("paymentStage", paymentStage);
        return jdbc.selectOne("com.susukkang.fgc.transaction.performance.baseline.PaymentCapCheckJdbcBaseline.selectComplianceEvidenceAmount", parameters);
    }
    public boolean existsApplicableRuleSet(Long contractId, PaymentStage paymentStage) {
        var parameters = new HashMap<String, Object>();
        parameters.put("contractId", contractId);
        parameters.put("paymentStage", paymentStage);
        return jdbc.selectOne("com.susukkang.fgc.transaction.performance.baseline.PaymentCapCheckJdbcBaseline.existsApplicableRuleSet", parameters);
    }
}
