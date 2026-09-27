package com.susukkang.fgc.contract.repository;
import com.susukkang.fgc.contract.entity.ContractFinancialSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 설명 : 계약 재무 스냅샷을 저장·조회하고 계약일 기준 최초 스냅샷을 중복 없이 생성한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-27
 */


public interface ContractFinancialSnapshotRepository
        extends JpaRepository<ContractFinancialSnapshot, Long> {

    /**
     * 계약일 기준 최초 재무 스냅샷을 생성한다.
     *
     * @param contractId 보험계약 ID
     * @return 삽입한 행 수. 이미 존재하거나 계약이 없으면 0
     */
    @Modifying
    @Query(value = """
            INSERT INTO fgc.contract_financial_snapshot (
                contract_id,
                as_of_date,
                contract_month_no,
                cumulative_paid_premium,
                surrender_value,
                surrender_value_type,
                refund_rate_table_id,
                source_ref
            )
            SELECT c.contract_id,
                   c.contract_date,
                   1,
                   c.first_premium_amount,
                   NULL,
                   'EXPECTED_TABLE',
                   NULL,
                   'MANUAL_CONTRACT_INITIAL'
              FROM fgc.insurance_contract c
             WHERE c.contract_id = :contractId
            ON CONFLICT ON CONSTRAINT uq_contract_financial_snapshot
            DO NOTHING
            """, nativeQuery = true)
    int insertInitialIfAbsent(@Param("contractId") Long contractId);
}
