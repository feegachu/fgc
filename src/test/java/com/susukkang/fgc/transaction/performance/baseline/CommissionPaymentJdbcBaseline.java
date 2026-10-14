package com.susukkang.fgc.transaction.performance.baseline;

import com.susukkang.fgc.common.code.AgentRankCode;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.transaction.domain.AttributedContractNo;
import com.susukkang.fgc.transaction.domain.CapCheckCommand;
import com.susukkang.fgc.transaction.domain.CapRuleSnapshot;
import com.susukkang.fgc.transaction.domain.CommissionItemReference;
import com.susukkang.fgc.transaction.domain.CommissionPaymentCommand;
import com.susukkang.fgc.transaction.domain.CommissionPaymentAttributionCommand;
import com.susukkang.fgc.transaction.domain.CommissionPaymentAttributionRow;
import com.susukkang.fgc.transaction.domain.CommissionPaymentRow;
import com.susukkang.fgc.transaction.domain.ConfirmationData;
import com.susukkang.fgc.transaction.domain.ContractReference;
import com.susukkang.fgc.transaction.domain.ExceptionCaseCommand;
import com.susukkang.fgc.transaction.domain.ExistingIncludedDetail;
import com.susukkang.fgc.transaction.dto.CommissionPaymentListResponse;
import com.susukkang.fgc.transaction.dto.CommissionPaymentSearchCondition;
import java.time.LocalDate;
import java.util.List;
import com.susukkang.fgc.common.persistence.baseline.JdbcBaseline;
import javax.sql.DataSource;
import java.util.HashMap;

/** 전환 전 SQL을 실행하는 테스트 전용 JDBC 기준 구현. */
public class CommissionPaymentJdbcBaseline {
    private final JdbcBaseline jdbc;
    public CommissionPaymentJdbcBaseline(DataSource dataSource) { this.jdbc = new JdbcBaseline(dataSource); }

    public List<CommissionPaymentListResponse> selectByCondition(CommissionPaymentSearchCondition condition, int size, long offset) {
        var parameters = new HashMap<String, Object>();
        parameters.put("condition", condition);
        parameters.put("size", size);
        parameters.put("offset", offset);
        return jdbc.selectList("com.susukkang.fgc.transaction.performance.baseline.CommissionPaymentJdbcBaseline.selectByCondition", parameters);
    }
    public long countByCondition(CommissionPaymentSearchCondition condition) {
        var parameters = new HashMap<String, Object>();
        parameters.put("condition", condition);
        return jdbc.selectOne("com.susukkang.fgc.transaction.performance.baseline.CommissionPaymentJdbcBaseline.countByCondition", parameters);
    }
    public boolean existsAgent(Long agentId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("agentId", agentId);
        return jdbc.selectOne("com.susukkang.fgc.transaction.performance.baseline.CommissionPaymentJdbcBaseline.existsAgent", parameters);
    }
    public boolean existsEligibleNewcomerSupportAgent(Long agentId, LocalDate asOfDate) {
        var parameters = new HashMap<String, Object>();
        parameters.put("agentId", agentId);
        parameters.put("asOfDate", asOfDate);
        return jdbc.selectOne("com.susukkang.fgc.transaction.performance.baseline.CommissionPaymentJdbcBaseline.existsEligibleNewcomerSupportAgent", parameters);
    }
    public LocalDate findAgentAppointmentDate(Long agentId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("agentId", agentId);
        return jdbc.selectOne("com.susukkang.fgc.transaction.performance.baseline.CommissionPaymentJdbcBaseline.findAgentAppointmentDate", parameters);
    }
    public AgentRankCode findAgentRankCode(Long agentId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("agentId", agentId);
        return jdbc.selectOne("com.susukkang.fgc.transaction.performance.baseline.CommissionPaymentJdbcBaseline.findAgentRankCode", parameters);
    }
    public String findProtectionType(Long contractId) {
        return jdbc.selectOne("com.susukkang.fgc.transaction.performance.baseline.CommissionPaymentJdbcBaseline.findProtectionType",
                java.util.Map.of("contractId", contractId));
    }
    public ContractReference findContract(Long contractId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("contractId", contractId);
        return jdbc.selectOne("com.susukkang.fgc.transaction.performance.baseline.CommissionPaymentJdbcBaseline.findContract", parameters);
    }
    public int countContractsBeforeMonth(Long agentId, LocalDate monthStart) {
        var parameters = new HashMap<String, Object>();
        parameters.put("agentId", agentId);
        parameters.put("monthStart", monthStart);
        return jdbc.selectOne("com.susukkang.fgc.transaction.performance.baseline.CommissionPaymentJdbcBaseline.countContractsBeforeMonth", parameters);
    }
    public CommissionItemReference findCommissionItem(Long commissionItemId, LocalDate asOf) {
        var parameters = new HashMap<String, Object>();
        parameters.put("commissionItemId", commissionItemId);
        parameters.put("asOf", asOf);
        return jdbc.selectOne("com.susukkang.fgc.transaction.performance.baseline.CommissionPaymentJdbcBaseline.findCommissionItem", parameters);
    }
    public Long findAllocationPolicyId(Long policyVersionId, String allocationBasis) {
        var parameters = new HashMap<String, Object>();
        parameters.put("policyVersionId", policyVersionId);
        parameters.put("allocationBasis", allocationBasis);
        return jdbc.selectOne("com.susukkang.fgc.transaction.performance.baseline.CommissionPaymentJdbcBaseline.findAllocationPolicyId", parameters);
    }
    public boolean existsPolicyVersion(Long policyVersionId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("policyVersionId", policyVersionId);
        return jdbc.selectOne("com.susukkang.fgc.transaction.performance.baseline.CommissionPaymentJdbcBaseline.existsPolicyVersion", parameters);
    }
    public void insertTransaction(CommissionPaymentCommand command) {
        jdbc.update("com.susukkang.fgc.transaction.performance.baseline.CommissionPaymentJdbcBaseline.insertTransaction", command);
    }
    public int updateTransaction(CommissionPaymentCommand command) {
        return jdbc.update("com.susukkang.fgc.transaction.performance.baseline.CommissionPaymentJdbcBaseline.updateTransaction", command);
    }
    public void detachPreConfirmDetails(Long paymentId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("paymentId", paymentId);
        jdbc.update("com.susukkang.fgc.transaction.performance.baseline.CommissionPaymentJdbcBaseline.detachPreConfirmDetails", parameters);
    }
    public void deleteAttributions(Long paymentId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("paymentId", paymentId);
        jdbc.update("com.susukkang.fgc.transaction.performance.baseline.CommissionPaymentJdbcBaseline.deleteAttributions", parameters);
    }
    public void insertAttributions(List<CommissionPaymentAttributionCommand> attributions) {
        var parameters = new HashMap<String, Object>();
        parameters.put("attributions", attributions);
        jdbc.update("com.susukkang.fgc.transaction.performance.baseline.CommissionPaymentJdbcBaseline.insertAttributions", parameters);
    }
    public CommissionPaymentRow findById(Long paymentId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("paymentId", paymentId);
        return jdbc.selectOne("com.susukkang.fgc.transaction.performance.baseline.CommissionPaymentJdbcBaseline.findById", parameters);
    }
    public List<CommissionPaymentAttributionRow> findAttributions(Long paymentId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("paymentId", paymentId);
        return jdbc.selectList("com.susukkang.fgc.transaction.performance.baseline.CommissionPaymentJdbcBaseline.findAttributions", parameters);
    }
    public List<ConfirmationData> findConfirmationDataForUpdate(Long paymentId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("paymentId", paymentId);
        return jdbc.selectList("com.susukkang.fgc.transaction.performance.baseline.CommissionPaymentJdbcBaseline.findConfirmationDataForUpdate", parameters);
    }
    public List<ConfirmationData> findConfirmationData(Long paymentId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("paymentId", paymentId);
        return jdbc.selectList("com.susukkang.fgc.transaction.performance.baseline.CommissionPaymentJdbcBaseline.findConfirmationData", parameters);
    }
    public List<AttributedContractNo> findAttributedContractNumbers(Long paymentId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("paymentId", paymentId);
        return jdbc.selectList("com.susukkang.fgc.transaction.performance.baseline.CommissionPaymentJdbcBaseline.findAttributedContractNumbers", parameters);
    }
    public List<Long> lockAttributedContracts(Long paymentId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("paymentId", paymentId);
        return jdbc.selectList("com.susukkang.fgc.transaction.performance.baseline.CommissionPaymentJdbcBaseline.lockAttributedContracts", parameters);
    }
    public CapRuleSnapshot findCapRuleSnapshot(Long paymentId, Long transactionAttributionId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("paymentId", paymentId);
        parameters.put("transactionAttributionId", transactionAttributionId);
        return jdbc.selectOne("com.susukkang.fgc.transaction.performance.baseline.CommissionPaymentJdbcBaseline.findCapRuleSnapshot", parameters);
    }
    public List<Long> findOperationalScheduleLineIds(Long contractId, PaymentStage paymentStage, Long commissionItemId, Long beneficiaryAgentId, LocalDate attributionMonthStart, LocalDate nextAttributionMonthStart) {
        var parameters = new HashMap<String, Object>();
        parameters.put("contractId", contractId);
        parameters.put("paymentStage", paymentStage);
        parameters.put("commissionItemId", commissionItemId);
        parameters.put("beneficiaryAgentId", beneficiaryAgentId);
        parameters.put("attributionMonthStart", attributionMonthStart);
        parameters.put("nextAttributionMonthStart", nextAttributionMonthStart);
        return jdbc.selectList("com.susukkang.fgc.transaction.performance.baseline.CommissionPaymentJdbcBaseline.findOperationalScheduleLineIds", parameters);
    }
    public List<Integer> findOperationalScheduleInstallmentNos(Long contractId, PaymentStage paymentStage, Long commissionItemId, Long beneficiaryAgentId, LocalDate attributionMonthStart, LocalDate nextAttributionMonthStart) {
        var parameters = new HashMap<String, Object>();
        parameters.put("contractId", contractId);
        parameters.put("paymentStage", paymentStage);
        parameters.put("commissionItemId", commissionItemId);
        parameters.put("beneficiaryAgentId", beneficiaryAgentId);
        parameters.put("attributionMonthStart", attributionMonthStart);
        parameters.put("nextAttributionMonthStart", nextAttributionMonthStart);
        return jdbc.selectList("com.susukkang.fgc.transaction.performance.baseline.CommissionPaymentJdbcBaseline.findOperationalScheduleInstallmentNos", parameters);
    }
    public boolean existsApplicableCapRuleSet(Long paymentId, Long transactionAttributionId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("paymentId", paymentId);
        parameters.put("transactionAttributionId", transactionAttributionId);
        return jdbc.selectOne("com.susukkang.fgc.transaction.performance.baseline.CommissionPaymentJdbcBaseline.existsApplicableCapRuleSet", parameters);
    }
    public void insertCapCheck(CapCheckCommand command) {
        jdbc.update("com.susukkang.fgc.transaction.performance.baseline.CommissionPaymentJdbcBaseline.insertCapCheck", command);
    }
    public List<ExistingIncludedDetail> findExistingIncludedDetails(Long paymentId, Long transactionAttributionId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("paymentId", paymentId);
        parameters.put("transactionAttributionId", transactionAttributionId);
        return jdbc.selectList("com.susukkang.fgc.transaction.performance.baseline.CommissionPaymentJdbcBaseline.findExistingIncludedDetails", parameters);
    }
    public void insertExceptionCase(ExceptionCaseCommand command) {
        jdbc.update("com.susukkang.fgc.transaction.performance.baseline.CommissionPaymentJdbcBaseline.insertExceptionCase", command);
    }
    public List<Long> findCapCheckIds(Long paymentId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("paymentId", paymentId);
        return jdbc.selectList("com.susukkang.fgc.transaction.performance.baseline.CommissionPaymentJdbcBaseline.findCapCheckIds", parameters);
    }
    public int confirm(Long paymentId, String idempotencyKey, String capCheckIdsCsv) {
        var parameters = new HashMap<String, Object>();
        parameters.put("paymentId", paymentId);
        parameters.put("idempotencyKey", idempotencyKey);
        parameters.put("capCheckIdsCsv", capCheckIdsCsv);
        return jdbc.update("com.susukkang.fgc.transaction.performance.baseline.CommissionPaymentJdbcBaseline.confirm", parameters);
    }
}
