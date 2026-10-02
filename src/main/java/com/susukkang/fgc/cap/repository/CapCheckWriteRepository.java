package com.susukkang.fgc.cap.repository;

import com.susukkang.fgc.cap.dto.CapCheckInsertRow;
import com.susukkang.fgc.cap.dto.CapCheckDetailInsertRow;
import com.susukkang.fgc.cap.entity.CapCheck;
import com.susukkang.fgc.common.code.CapCheckKind;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.transaction.domain.CapCheckCommand;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/**
 * 설명 : 지급 확정·일반 한도 계산이 공유하는 결과 저장 계약.
 * 업무키 UPSERT와 상세 일괄 저장은 PostgreSQL 원자성을 유지한다.
 * 네이티브 변경 전 flush, 변경 후 clear로 기존 관리 엔티티의 오래된 결과 재사용을 막는다.
 * 호출자가 시작한 트랜잭션에 참여하며 개별 저장을 별도 커밋하지 않는다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-30
 */
@Repository
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class CapCheckWriteRepository {
    private final EntityManager entityManager;
    private final CapCheckRepository capCheckRepository;
    private final CapCheckDetailRepository capCheckDetailRepository;

    private static final String UPSERT = """
            INSERT INTO fgc.cap_check (
                validation_run_id, contract_id, payment_stage, cap_rule_set_id, refund_rate_table_id,
                check_kind, as_of_date, base_premium_amount, refund_12m_amount, compliance_deduction_amount,
                limit_amount, included_amount, remaining_amount, usage_pct, result_status, calculation_snapshot
            ) VALUES (
                :validationRunId, :contractId, :paymentStage, :capRuleSetId, :refundRateTableId,
                :checkKind, :asOfDate, :basePremiumAmount, :refund12mAmount, :complianceDeductionAmount,
                :limitAmount, :includedAmount, :remainingAmount, :usagePct, :resultStatus,
                CAST(COALESCE(:calculationSnapshotJson, '{}') AS jsonb)
            )
            ON CONFLICT ON CONSTRAINT uq_cap_check_monthly DO UPDATE SET
                cap_rule_set_id              = EXCLUDED.cap_rule_set_id,
                refund_rate_table_id         = EXCLUDED.refund_rate_table_id,
                check_kind                   = EXCLUDED.check_kind,
                as_of_date                   = EXCLUDED.as_of_date,
                base_premium_amount          = EXCLUDED.base_premium_amount,
                refund_12m_amount            = EXCLUDED.refund_12m_amount,
                compliance_deduction_amount  = EXCLUDED.compliance_deduction_amount,
                limit_amount                 = EXCLUDED.limit_amount,
                included_amount              = EXCLUDED.included_amount,
                remaining_amount             = EXCLUDED.remaining_amount,
                usage_pct                    = EXCLUDED.usage_pct,
                result_status                = EXCLUDED.result_status,
                calculation_snapshot         = EXCLUDED.calculation_snapshot,
                checked_at                   = clock_timestamp()
            RETURNING cap_check_id
            """;

    private static final String DETAIL_INSERT = """
            INSERT INTO fgc.cap_check_detail (
                cap_check_id, detail_seq, commission_item_id, item_code, item_name,
                schedule_line_id, transaction_attribution_id, contract_month_no, classification_snapshot, amount, decision_reason, evidence_ref
            ) VALUES
            """;

    private static final String DETAIL_CONFLICT = """
            ON CONFLICT ON CONSTRAINT uq_cap_check_detail DO UPDATE SET
                commission_item_id      = EXCLUDED.commission_item_id,
                item_code                = EXCLUDED.item_code,
                item_name                = EXCLUDED.item_name,
                schedule_line_id         = EXCLUDED.schedule_line_id,
                transaction_attribution_id = EXCLUDED.transaction_attribution_id,
                contract_month_no        = EXCLUDED.contract_month_no,
                classification_snapshot  = EXCLUDED.classification_snapshot,
                amount                   = EXCLUDED.amount,
                decision_reason          = EXCLUDED.decision_reason,
                evidence_ref             = EXCLUDED.evidence_ref
            """;

    /** 같은 실행의 판정은 기존 ID를 재사용하고 REALTIME(null 실행)은 새 판정을 추가한다. */
    public void insertCapCheck(CapCheckInsertRow row) {
        entityManager.flush();
        NativeQuery<?> query = entityManager.createNativeQuery(UPSERT).unwrap(NativeQuery.class);
        query.setParameter("validationRunId", row.getValidationRunId(), Long.class);
        query.setParameter("contractId", row.getContractId(), Long.class);
        query.setParameter("paymentStage", row.getPaymentStage(), String.class);
        query.setParameter("capRuleSetId", row.getCapRuleSetId(), Long.class);
        query.setParameter("refundRateTableId", row.getRefundRateTableId(), Long.class);
        query.setParameter("checkKind", row.getCheckKind(), String.class);
        query.setParameter("asOfDate", row.getAsOfDate(), java.time.LocalDate.class);
        query.setParameter("basePremiumAmount", row.getBasePremiumAmount(), java.math.BigDecimal.class);
        query.setParameter("refund12mAmount", row.getRefund12mAmount(), java.math.BigDecimal.class);
        query.setParameter("complianceDeductionAmount", row.getComplianceDeductionAmount(), java.math.BigDecimal.class);
        query.setParameter("limitAmount", row.getLimitAmount(), java.math.BigDecimal.class);
        query.setParameter("includedAmount", row.getIncludedAmount(), java.math.BigDecimal.class);
        query.setParameter("remainingAmount", row.getRemainingAmount(), java.math.BigDecimal.class);
        query.setParameter("usagePct", row.getUsagePct(), java.math.BigDecimal.class);
        query.setParameter("resultStatus", row.getResultStatus(), String.class);
        query.setParameter("calculationSnapshotJson", row.getCalculationSnapshotJson(), String.class);
        row.setCapCheckId(((Number) query.getSingleResult()).longValue());
        entityManager.clear();
    }

    /** 지급 확정 후보는 기존 판정을 덮어쓰지 않는 신규 엔티티로 저장한다. */
    public void insertCapCheck(CapCheckCommand command) {
        CapCheck entity = CapCheck.builder()
                .contractId(command.getContractId())
                .paymentStage(PaymentStage.valueOf(command.getPaymentStage()))
                .capRuleSetId(command.getCapRuleSetId())
                .refundRateTableId(command.getRefundRateTableId())
                .candidateTransactionId(command.getPaymentId())
                .checkKind(CapCheckKind.PRE_CONFIRM)
                .asOfDate(command.getAsOfDate())
                .basePremiumAmount(command.getBasePremiumAmount())
                .refund12mAmount(command.getRefund12mAmount())
                .complianceDeductionAmount(command.getComplianceDeductionAmount())
                .limitAmount(command.getLimitAmount())
                .includedAmount(command.getIncludedAmount())
                .remainingAmount(command.getRemainingAmount())
                .usagePct(command.getUsagePct())
                .resultStatus(command.getResultStatus())
                .calculationSnapshotJson(command.getCalculationSnapshotJson())
                .build();
        command.setCapCheckId(capCheckRepository.saveAndFlush(entity).getCapCheckId());
    }

    public void insertCapCheckDetails(List<CapCheckDetailInsertRow> details) {
        if (details.isEmpty()) {
            return;
        }
        entityManager.flush();
        StringBuilder sql = new StringBuilder(DETAIL_INSERT);
        for (int index = 0; index < details.size(); index++) {
            if (index > 0) {
                sql.append(", ");
            }
            sql.append("(");
            sql.append(":capCheckId" + index +
                    ", :detailSeq" + index +
                    ", :commissionItemId" + index +
                    ", :itemCode" + index +
                    ", :itemName" + index +
                    ", :scheduleLineId" + index +
                    ", :transactionAttributionId" + index +
                    ", :contractMonthNo" + index +
                    ", :classificationSnapshot" + index +
                    ", :amount" + index +
                    ", :decisionReason" + index +
                    ", :evidenceRef" + index);
            sql.append(")");
        }
        sql.append(" ").append(DETAIL_CONFLICT);
        NativeQuery<?> query = entityManager.createNativeQuery(sql.toString()).unwrap(NativeQuery.class);
        for (int index = 0; index < details.size(); index++) {
            CapCheckDetailInsertRow row = details.get(index);
            query.setParameter("capCheckId" + index, row.getCapCheckId(), Long.class);
            query.setParameter("detailSeq" + index, row.getDetailSeq(), Integer.class);
            query.setParameter("commissionItemId" + index, row.getCommissionItemId(), Long.class);
            query.setParameter("itemCode" + index, row.getItemCode(), String.class);
            query.setParameter("itemName" + index, row.getItemName(), String.class);
            query.setParameter("scheduleLineId" + index, row.getScheduleLineId(), Long.class);
            query.setParameter("transactionAttributionId" + index, row.getTransactionAttributionId(), Long.class);
            query.setParameter("contractMonthNo" + index, row.getContractMonthNo(), Integer.class);
            query.setParameter("classificationSnapshot" + index, row.getClassificationSnapshot(), String.class);
            query.setParameter("amount" + index, row.getAmount(), java.math.BigDecimal.class);
            query.setParameter("decisionReason" + index, row.getDecisionReason(), String.class);
            query.setParameter("evidenceRef" + index, row.getEvidenceRef(), String.class);
        }
        query.executeUpdate();
        entityManager.clear();
    }

    /** 재계산 결과에서 없어진 뒷자리만 삭제한다. */
    public void pruneCapCheckDetails(Long capCheckId, int maxDetailSeq) {
        capCheckDetailRepository.deleteObsoleteDetails(capCheckId, maxDetailSeq);
    }

    /** 지급 수정 전 기존 사전점검 스냅샷은 보존하고 삭제할 귀속행의 참조만 해제한다. */
    public void detachPreConfirmDetails(Long paymentId) {
        entityManager.flush();
        entityManager.createNativeQuery("""
                UPDATE fgc.cap_check_detail ccd
                   SET transaction_attribution_id = NULL
                  FROM fgc.cap_check cc
                 WHERE ccd.cap_check_id = cc.cap_check_id
                   AND cc.candidate_transaction_id = :paymentId
                   AND cc.check_kind = 'PRE_CONFIRM'
                """)
                .setParameter("paymentId", paymentId)
                .executeUpdate();
        entityManager.clear();
    }
}
