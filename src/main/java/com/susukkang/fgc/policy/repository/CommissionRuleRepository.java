package com.susukkang.fgc.policy.repository;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.policy.dto.CommissionRuleRow;
import com.susukkang.fgc.policy.dto.ResolvedCommissionRule;
import com.susukkang.fgc.policy.entity.CommissionRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** 정책 상세 및 계약에 적용할 수수료 규칙 조회. */
public interface CommissionRuleRepository extends JpaRepository<CommissionRule, Long> {

    @Query("""
            SELECT new com.susukkang.fgc.policy.dto.CommissionRuleRow(
                cr.commissionRuleId, CAST(cr.paymentStage AS String), i.insurerName, p.productName,
                CAST(cr.agentRankCode AS String), ci.itemCode, ci.itemName,
                CAST(cr.feeComponentType AS String), cr.installmentFrom, cr.installmentTo,
                CAST(cr.calculationType AS String), cr.basisCode, cr.ratePct, cr.fixedAmount, cr.priorityNo
            )
            FROM CommissionRule cr
            JOIN CommissionItem ci ON ci.commissionItemId = cr.commissionItemId
            LEFT JOIN Insurer i ON i.insurerId = cr.insurerId
            LEFT JOIN ProductOffering po ON po.productOfferingId = cr.productOfferingId
            LEFT JOIN Product p ON p.productId = po.productId
            WHERE cr.policyVersionId = :policyVersionId
            ORDER BY cr.paymentStage, cr.installmentFrom, cr.ratePct DESC NULLS LAST, cr.commissionRuleId
            """)
    List<CommissionRuleRow> selectCommissionRules(@Param("policyVersionId") Long policyVersionId);

    @NativeQuery(value = """
            SELECT cr.fee_component_type,
                   cr.commission_rule_id,
                   cr.commission_item_id,
                   cr.agent_rank_code,
                   cr.installment_from,
                   cr.installment_to,
                   cr.basis_code,
                   cr.calculation_type,
                   cr.rate_pct,
                   cr.fixed_amount,
                   cr.rounding_scale,
                   cr.rounding_mode,
                   cr.payment_condition_code,
                   cr.insurer_id,
                   cr.organization_id,
                   cr.product_offering_id,
                   cr.priority_no
            FROM fgc.commission_rule cr
            JOIN fgc.insurance_contract c ON c.contract_id = :contractId
            WHERE cr.policy_version_id = :policyVersionId
              AND cr.payment_stage = :#{#paymentStage?.name()}
              AND (cr.insurer_id IS NULL OR cr.insurer_id = c.insurer_id)
              AND (cr.product_offering_id IS NULL OR cr.product_offering_id = c.product_offering_id)
              AND (cr.organization_id IS NULL OR cr.organization_id = c.organization_id)
            ORDER BY cr.priority_no, cr.installment_from, cr.commission_rule_id
            """, sqlResultSetMapping = "ResolvedCommissionRuleMapping")
    List<ResolvedCommissionRule> findApplicableCommissionRules(
            @Param("policyVersionId") Long policyVersionId,
            @Param("contractId") Long contractId,
            @Param("paymentStage") PaymentStage paymentStage
    );
}
