package com.susukkang.fgc.transaction.mapper;

import com.susukkang.fgc.transaction.domain.CapCheckCommand;
import com.susukkang.fgc.transaction.domain.CapRuleSnapshot;
import com.susukkang.fgc.transaction.domain.CommissionItemReference;
import com.susukkang.fgc.transaction.domain.CommissionPaymentCommand;
import com.susukkang.fgc.transaction.domain.CommissionPaymentRow;
import com.susukkang.fgc.transaction.domain.ConfirmationData;
import com.susukkang.fgc.transaction.domain.ContractReference;
import com.susukkang.fgc.transaction.domain.ExceptionCaseCommand;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

@Mapper
public interface CommissionPaymentMapper {

    boolean existsAgent(@Param("agentId") Long agentId);

    ContractReference findContract(@Param("contractId") Long contractId);

    int countEarlierContracts(
            @Param("agentId") Long agentId,
            @Param("contractId") Long contractId,
            @Param("contractDate") LocalDate contractDate
    );

    CommissionItemReference findCommissionItem(
            @Param("itemCode") String itemCode,
            @Param("asOf") LocalDate asOf
    );

    Long findAllocationPolicyId(
            @Param("policyVersionId") Long policyVersionId,
            @Param("allocationBasis") String allocationBasis
    );

    boolean existsPolicyVersion(@Param("policyVersionId") Long policyVersionId);

    void insertTransaction(CommissionPaymentCommand command);

    void updateTransaction(CommissionPaymentCommand command);

    void deleteAttributions(@Param("paymentId") Long paymentId);

    void insertAttribution(CommissionPaymentCommand command);

    CommissionPaymentRow findById(@Param("paymentId") Long paymentId);

    ConfirmationData findConfirmationDataForUpdate(@Param("paymentId") Long paymentId);

    CapRuleSnapshot findCapRuleSnapshot(@Param("paymentId") Long paymentId);

    void insertCapCheck(CapCheckCommand command);

    void insertCapCheckDetail(CapCheckCommand command);

    void insertExceptionCase(ExceptionCaseCommand command);

    int confirm(@Param("paymentId") Long paymentId);
}
