package com.susukkang.fgc.validation.repository;

import com.susukkang.fgc.validation.dto.ValidationTargetListRow;
import com.susukkang.fgc.validation.entity.ValidationTarget;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 설명 : validation_target 공유 Repository. 대상 선별 결과의 생성(네이티브 벌크 INSERT)과
 * 조회(선정된 contractId 목록)를 제공한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-10-03
 */
public interface ValidationTargetRepository extends JpaRepository<ValidationTarget, Long> {

    /**
     * asOfDate 기준 활성 계약 중 환급률표 매칭 결과로 선정 상태(SELECTED/EXCLUDED/REVIEW_REQUIRED)를
     * 판정해 벌크 INSERT한다. 상품코드 비교·집합 연산(FILTER, GROUP BY)이 DB 전용 로직이라
     * 엔티티 조작 대신 네이티브 SQL을 그대로 유지한다(기존 MyBatis SQL과 동일).
     * {@code ON CONFLICT (validation_run_id, contract_id) DO NOTHING}으로 재시도 멱등성을 보장한다.
     *
     * @return 실제로 삽입된 행 수(충돌로 스킵된 행은 제외)
     */
    @Modifying
    @Query(value = """
            WITH eligible_contract AS (
                SELECT ic.contract_id,
                       ic.product_offering_id,
                       ic.contract_date,
                       ic.current_status,
                       ic.insurer_id,
                       ic.payment_term_months,
                       po.product_id,
                       po.channel_code,
                       p.standard_product_code
                  FROM insurance_contract ic
                  JOIN product_offering po
                    ON po.product_offering_id = ic.product_offering_id
                   AND po.active_yn = true
                   AND po.sales_start_date <= ic.contract_date
                   AND (po.sales_end_date IS NULL OR po.sales_end_date >= ic.contract_date)
                  JOIN product p
                    ON p.product_id = po.product_id
                   AND p.active_yn = true
                 WHERE ic.current_status = 'ACTIVE'
                   AND ic.contract_date <= :asOfDate
            ),
            candidate AS (
                SELECT ec.*,
                       rrt.refund_rate_table_id,
                       (rrt.source_product_code = ec.standard_product_code) AS product_code_matches
                  FROM eligible_contract ec
                  LEFT JOIN refund_rate_table rrt
                    ON rrt.insurer_id = ec.insurer_id
                   AND rrt.product_id = ec.product_id
                   AND rrt.payment_term_months = ec.payment_term_months
                   AND rrt.channel_code = ec.channel_code
                   AND rrt.effective_from <= :asOfDate
                   AND (rrt.effective_to IS NULL OR rrt.effective_to >= :asOfDate)
                   AND EXISTS (
                        SELECT 1
                          FROM policy_version pv
                         WHERE pv.policy_version_id = rrt.policy_version_id
                           AND pv.status = 'ACTIVE'
                           AND pv.effective_from <= :asOfDate
                           AND (pv.effective_to IS NULL OR pv.effective_to >= :asOfDate)
                   )
                   AND EXISTS (
                        SELECT 1
                          FROM refund_rate_line rrl
                         WHERE rrl.refund_rate_table_id = rrt.refund_rate_table_id
                           AND rrl.contract_month_no = 12
                   )
            ),
            resolved AS (
                SELECT contract_id,
                       product_offering_id,
                       contract_date,
                       current_status,
                       insurer_id,
                       payment_term_months,
                       channel_code,
                       standard_product_code,
                       COUNT(refund_rate_table_id) AS candidate_count,
                       COUNT(refund_rate_table_id) FILTER (WHERE product_code_matches) AS matching_count,
                       MIN(refund_rate_table_id) FILTER (WHERE product_code_matches) AS refund_rate_table_id
                  FROM candidate
                 GROUP BY contract_id, product_offering_id, contract_date, current_status,
                          insurer_id, payment_term_months, channel_code, standard_product_code
            )
            INSERT INTO validation_target (
                validation_run_id,
                contract_id,
                product_offering_id,
                refund_rate_table_id,
                selection_status,
                selection_reason,
                snapshot
            )
            SELECT CAST(:validationRunId AS bigint),
                   contract_id,
                   product_offering_id,
                   CASE WHEN matching_count = 1 THEN refund_rate_table_id END,
                   CASE WHEN matching_count = 1 THEN 'SELECTED' ELSE 'REVIEW_REQUIRED' END,
                   CASE
                       WHEN candidate_count = 0 THEN '예상 해약환급률표 없음'
                       WHEN matching_count = 0 THEN '상품코드 불일치'
                       WHEN matching_count > 1 THEN '예상 해약환급률표 중복'
                       ELSE '월 통합검증 대상'
                   END,
                   jsonb_build_object(
                       'validationMonth', CAST(:validationMonth AS date),
                       'asOfDate', CAST(:asOfDate AS date),
                       'contractDate', contract_date,
                       'contractStatus', current_status,
                       'insurerId', insurer_id,
                       'paymentTermMonths', payment_term_months,
                       'channelCode', channel_code,
                       'standardProductCode', standard_product_code,
                       'refundRateCandidateCount', candidate_count,
                       'refundRateMatchingCount', matching_count
                   )
              FROM resolved
            ON CONFLICT (validation_run_id, contract_id)
            DO NOTHING
            """, nativeQuery = true)
    int insertTargets(@Param("validationRunId") Long validationRunId,
                       @Param("validationMonth") LocalDate validationMonth,
                       @Param("asOfDate") LocalDate asOfDate);

    /** 실행 내에서 선정(SELECTED)된 계약 id 목록, contractId 오름차순. */
    @Query("""
            SELECT t.contractId FROM ValidationTarget t
             WHERE t.validationRunId = :validationRunId AND t.selectionStatus = 'SELECTED'
             ORDER BY t.contractId
            """)
    List<Long> selectSelectedContractIds(@Param("validationRunId") Long validationRunId);

    /**
     * IF-API-47 상세 화면 ③ 대상 선별 결과. 검토필요·제외 먼저, 이후 id 순으로 limit건.
     * product_offering은 계약이 아니라 validation_target 자신의 productOfferingId로 조인한다
     * (원본 SQL과 동일 — 계약이 그 사이 다른 판매버전으로 바뀌어도 선별 시점 스냅샷을 보여준다).
     */
    @Query("""
            SELECT new com.susukkang.fgc.validation.dto.ValidationTargetListRow(
                t.validationTargetId, c.contractNo, t.selectionStatus, p.productName,
                po.offeringVersion, t.refundRateTableId, t.selectionReason)
            FROM ValidationTarget t
            JOIN InsuranceContract c ON c.contractId = t.contractId
            JOIN ProductOffering po ON po.productOfferingId = t.productOfferingId
            JOIN Product p ON p.productId = po.productId
            WHERE t.validationRunId = :validationRunId
            ORDER BY CASE t.selectionStatus
                         WHEN 'REVIEW_REQUIRED' THEN 0
                         WHEN 'EXCLUDED' THEN 1
                         ELSE 2
                     END, t.validationTargetId
            """)
    List<ValidationTargetListRow> findTargets(@Param("validationRunId") Long validationRunId, Pageable pageable);
}
