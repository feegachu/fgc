package com.susukkang.fgc.contract.repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계약 잠금과 버전 조회는 별도 SQL로 실행하여 잠금 대기 이후의 커밋을 읽는다.
 * 기존 엔티티를 clear하지 않고 호출자 변경을 flush한 뒤 새 행만 추가한다.
 *
 * @author hjKang
 * @since 2026-10-11
 */
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ContractFinancialSnapshotWritesImpl implements ContractFinancialSnapshotWrites {
    private final EntityManager entityManager;

    @Override
    public int appendManualInitialIfChanged(Long contractId) {
        // 서비스의 공통 잠금과 같은 계약 행을 잠근다. Repository 단독 호출도 안전하게 처리한다.
        entityManager.flush();
        if (entityManager.createNativeQuery("""
                SELECT contract_id FROM fgc.insurance_contract
                 WHERE contract_id = :contractId FOR UPDATE
                """, Long.class).setParameter("contractId", contractId).getResultList().isEmpty()) return 0;
        return entityManager.createNativeQuery("""
                WITH current_contract AS (
                    SELECT contract_id, contract_date, first_premium_amount
                      FROM fgc.insurance_contract WHERE contract_id = :contractId
                ), latest AS (
                    SELECT fs.* FROM fgc.contract_financial_snapshot fs
                      JOIN current_contract c ON fs.contract_id = c.contract_id
                       AND fs.as_of_date = c.contract_date
                     WHERE fs.surrender_value_type = 'EXPECTED_TABLE'
                     ORDER BY fs.snapshot_version DESC, fs.contract_financial_snapshot_id DESC
                     LIMIT 1
                )
                INSERT INTO fgc.contract_financial_snapshot (
                    contract_id, as_of_date, contract_month_no, cumulative_paid_premium,
                    surrender_value_type, source_ref, snapshot_version, previous_snapshot_id
                )
                SELECT c.contract_id, c.contract_date, 1, c.first_premium_amount,
                       'EXPECTED_TABLE', 'MANUAL_CONTRACT_INITIAL',
                       COALESCE(previous.snapshot_version, 0) + 1, previous.contract_financial_snapshot_id
                  FROM current_contract c LEFT JOIN latest previous ON true
                 WHERE previous.contract_financial_snapshot_id IS NULL
                    OR (previous.source_ref = 'MANUAL_CONTRACT_INITIAL'
                        AND previous.contract_month_no = 1
                        AND previous.surrender_value IS NULL
                        AND previous.refund_rate_table_id IS NULL
                        AND previous.cumulative_paid_premium IS DISTINCT FROM c.first_premium_amount)
                """).setParameter("contractId", contractId).executeUpdate();
    }
}
