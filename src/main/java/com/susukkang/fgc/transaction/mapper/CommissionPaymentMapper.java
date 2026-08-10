package com.susukkang.fgc.transaction.mapper;

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
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 설명 : 수수료 지급 건과 한도 검증 데이터 접근 매퍼
 *
 * @author yslee
 * @since 2026-08-06
 * @version 1.2
 */
@Mapper
public interface CommissionPaymentMapper {

    boolean existsAgent(@Param("agentId") Long agentId);

    ContractReference findContract(@Param("contractId") Long contractId);

    // 2026-08-07 yslee - 최초 신계약 모집월 이전 계약 존재 여부 조회로 변경
    // 기존 코드: 선택 계약보다 이른 계약 수를 조회
    // 문제: 같은 최초 모집월에 먼저 체결된 계약까지 이월 귀속 판정에서 제외
    // 개선: 대상 모집월 시작일 이전 계약만 조회하여 REG-20 월 단위 귀속을 지원
    int countContractsBeforeMonth(
            @Param("agentId") Long agentId,
            @Param("monthStart") LocalDate monthStart
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

    void insertAttributions(@Param("attributions") List<CommissionPaymentAttributionCommand> attributions);

    CommissionPaymentRow findById(@Param("paymentId") Long paymentId);

    List<CommissionPaymentAttributionRow> findAttributions(@Param("paymentId") Long paymentId);

    List<ConfirmationData> findConfirmationDataForUpdate(@Param("paymentId") Long paymentId);

    CapRuleSnapshot findCapRuleSnapshot(
            @Param("paymentId") Long paymentId,
            @Param("transactionAttributionId") Long transactionAttributionId
    );

    void insertCapCheck(CapCheckCommand command);

    void insertCapCheckDetail(CapCheckCommand command);

    void insertExceptionCase(ExceptionCaseCommand command);

    int confirm(@Param("paymentId") Long paymentId);
}
