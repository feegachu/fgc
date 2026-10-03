package com.susukkang.fgc.exceptioncase.repository;

import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseActionTarget;
import com.susukkang.fgc.exceptioncase.dto.JournalCorrectionExceptionTarget;
import com.susukkang.fgc.exceptioncase.entity.ExceptionCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * 설명 : 공통 예외 저장 및 조치 직전 잠금·현재 상태 갱신 Repository
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-28
 */
public interface ExceptionCaseRepository extends JpaRepository<ExceptionCase, Long> {

    // 관리 중인 엔티티의 이전 상태를 재사용하지 않고 잠근 DB 행을 DTO로 읽는다.
    // 잠금은 후속 순번 조회·이력 저장·상태 갱신까지 유지돼야 하므로 호출자 트랜잭션이 필수다.
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
            SELECT exception_case_id AS "exceptionCaseId", exception_type AS "exceptionType",
                   status, assigned_to AS "assignedTo",
                   source_entity_type AS "sourceEntityType", source_entity_id AS "sourceEntityId"
              FROM fgc.exception_case
             WHERE exception_case_id = :exceptionCaseId
             FOR UPDATE
            """, nativeQuery = true)
    LockedCaseView findLockedById(@Param("exceptionCaseId") Long exceptionCaseId);

    default ExceptionCaseActionTarget findByIdForUpdate(Long exceptionCaseId) {
        LockedCaseView row = findLockedById(exceptionCaseId);
        return row == null ? null : new ExceptionCaseActionTarget(
                row.getExceptionCaseId(), row.getExceptionType(),
                ExceptionStatus.valueOf(row.getStatus()), row.getAssignedTo());
    }

    default JournalCorrectionExceptionTarget findJournalCorrectionTargetForUpdate(Long exceptionCaseId) {
        LockedCaseView row = findLockedById(exceptionCaseId);
        return row == null ? null : new JournalCorrectionExceptionTarget(
                row.getExceptionCaseId(), row.getExceptionType(), ExceptionStatus.valueOf(row.getStatus()),
                row.getSourceEntityType(), row.getSourceEntityId());
    }

    // 이력을 먼저 DB에 반영하고 상태를 바꾼다. 변경 후에는 위 DTO 잠금 조회로 최신 상태를 읽는다.
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE ExceptionCase e
               SET e.status = :status, e.assignedTo = :assignedTo, e.resolvedAt = :resolvedAt
             WHERE e.exceptionCaseId = :exceptionCaseId
            """)
    int updateCaseAfterAction(@Param("exceptionCaseId") Long exceptionCaseId,
                              @Param("status") ExceptionStatus status,
                              @Param("assignedTo") Long assignedTo,
                              @Param("resolvedAt") OffsetDateTime resolvedAt);

    interface LockedCaseView {
        Long getExceptionCaseId();

        String getExceptionType();

        String getStatus();

        Long getAssignedTo();

        String getSourceEntityType();

        String getSourceEntityId();
    }

    // ── #380: 검증 배치가 소유하는 예외 탐지·생성 경로 ──────────────────────────
    // V23_1(SRC-032) 이후 전 메서드 공통 — fgc.record_exception_detection()을 호출한다.
    // 안정 업무키는 "{exception_type}:{YYYY-MM 검증월}:CONTRACT:..." 형태로, 실행 ID나
    // 실행별 결과 PK를 쓰지 않는다 — 같은 계약·같은 사유가 재실행에서도 같은 키가 돼
    // 새 업무건이 아니라 기존 건의 재검출(occurrence)로 잡힌다. 재검출 시 RESOLVED였던
    // 건은 함수가 자동으로 NEW로 되돌린다(reopened).
    //
    // 단건 메서드는 recorded_case_id를, 집계 메서드는 새로 생성된 "업무건" 수를
    // 반환한다(재검출로 이력만 추가된 건 세지 않는다 — ValidationRunExceptionServiceImpl
    // 누적 카운트가 반복 호출로 커지지 않는다).
    //
    // insertArbitrageCandidate/insertArbitrageReviewCase/bulkCreateFromReconciliationResultsByRun은
    // ArbitrageService(#373)·ReconciliationExceptionService(#377/#378)가 구 MyBatis
    // ExceptionCaseMapper를 아직 직접 주입해 쓰고 있어 이 Repository로 옮기지 않는다 —
    // 그 두 메서드와 ExceptionCaseMapper 인터페이스 자체는 그 소비자들이 전환한 뒤에야 지운다.

    /** 일일 변경 계약 재검증 실패를 검증월 기준 안정 업무키로 기록한다. */
    @Query(value = """
            SELECT recorded.recorded_case_id
              FROM fgc.validation_run vr
              CROSS JOIN LATERAL fgc.record_exception_detection(
                  CONCAT('DATA_QUALITY:', TO_CHAR(vr.validation_month, 'YYYY-MM'),
                         ':CONTRACT:', :contractId, ':',
                         CASE :title
                             WHEN '예상 스케줄 정합성 오류' THEN 'SCHEDULE_STRUCTURE_INVALID'
                             WHEN '예상 스케줄 정책 오류' THEN 'SCHEDULE_POLICY_INVALID'
                             WHEN '일일 변경 계약 재검증 실패' THEN 'CONTRACT_REVALIDATION_FAILED'
                             ELSE 'DATA_QUALITY'
                         END),
                  'DATA_QUALITY',
                  CASE :title
                      WHEN '예상 스케줄 정합성 오류' THEN 'SCHEDULE_STRUCTURE_INVALID'
                      WHEN '예상 스케줄 정책 오류' THEN 'SCHEDULE_POLICY_INVALID'
                      WHEN '일일 변경 계약 재검증 실패' THEN 'CONTRACT_REVALIDATION_FAILED'
                      ELSE 'DATA_QUALITY'
                  END,
                  'WARNING', :validationRunId, :contractId, NULL, NULL,
                  'INSURANCE_CONTRACT', CAST(:contractId AS varchar),
                  :title, :description,
                  jsonb_build_object('title', CAST(:title AS text), 'description', CAST(:description AS text))
              ) recorded
             WHERE vr.validation_run_id = :validationRunId
            """, nativeQuery = true)
    Long insertDataQualityCase(@Param("validationRunId") Long validationRunId,
                                @Param("contractId") Long contractId,
                                @Param("title") String title,
                                @Param("description") String description);

    /** 월 검증의 계약별 1,200% 계산 실패를 지급 단계별로 검증월 기준 안정 업무키로 기록한다. */
    @Query(value = """
            SELECT recorded.recorded_case_id
              FROM fgc.validation_run vr
              CROSS JOIN LATERAL fgc.record_exception_detection(
                  CONCAT('DATA_QUALITY:', TO_CHAR(vr.validation_month, 'YYYY-MM'),
                         ':CONTRACT:', :contractId, ':', :paymentStage),
                  'DATA_QUALITY', 'CAP_CALCULATION_FAILED', 'WARNING',
                  :validationRunId, :contractId, NULL, NULL,
                  'INSURANCE_CONTRACT', CAST(:contractId AS varchar),
                  CONCAT('1,200% 한도 검증 실패 - ', CAST(:paymentStage AS text)), :description,
                  jsonb_build_object('paymentStage', CAST(:paymentStage AS text), 'description', CAST(:description AS text))
              ) recorded
             WHERE vr.validation_run_id = :validationRunId
            """, nativeQuery = true)
    Long insertCapCheckFailure(@Param("validationRunId") Long validationRunId,
                                @Param("contractId") Long contractId,
                                @Param("paymentStage") String paymentStage,
                                @Param("description") String description);

    /** 월 검증 8단계: 이번 실행의 1,200% 한도 위반·검토필요 전부를 한 번에 탐지·기록한다. */
    @Query(value = """
            SELECT COUNT(*) FILTER (WHERE recorded.new_case)
              FROM fgc.cap_check cc
              JOIN fgc.validation_run vr ON vr.validation_run_id = cc.validation_run_id
              LEFT JOIN fgc.cap_rule_set crs ON crs.cap_rule_set_id = cc.cap_rule_set_id
              CROSS JOIN LATERAL fgc.record_exception_detection(
                  CONCAT(
                      CASE cc.result_status
                          WHEN 'VIOLATION' THEN 'CAP_VIOLATION'
                          WHEN 'REVIEW_REQUIRED' THEN 'CAP_REVIEW_REQUIRED'
                      END,
                      ':', TO_CHAR(vr.validation_month, 'YYYY-MM'),
                      ':CONTRACT:', cc.contract_id, ':', cc.payment_stage, ':', cc.cap_rule_set_id
                  ),
                  CASE cc.result_status
                      WHEN 'VIOLATION' THEN 'CAP_VIOLATION'
                      WHEN 'REVIEW_REQUIRED' THEN 'CAP_REVIEW_REQUIRED'
                  END,
                  CASE cc.result_status
                      WHEN 'VIOLATION' THEN 'CAP_LIMIT_VIOLATION'
                      WHEN 'REVIEW_REQUIRED' THEN 'CAP_CALCULATION_REVIEW_REQUIRED'
                  END,
                  CASE cc.result_status
                      WHEN 'VIOLATION' THEN 'CRITICAL'
                      WHEN 'REVIEW_REQUIRED' THEN 'WARNING'
                  END,
                  cc.validation_run_id, cc.contract_id, NULL, crs.policy_version_id,
                  'CAP_CHECK', CAST(cc.cap_check_id AS varchar),
                  CONCAT(
                      CASE cc.result_status
                          WHEN 'VIOLATION' THEN '1,200% 한도 초과'
                          WHEN 'REVIEW_REQUIRED' THEN '1,200% 한도 검토 필요'
                      END,
                      CASE WHEN cc.usage_pct IS NOT NULL
                           THEN CONCAT(' — 사용률 ', cc.usage_pct, '%')
                           ELSE CONCAT(' — 산입액 ', TO_CHAR(cc.included_amount, 'FM999,999,999,990'), '원')
                      END
                  ),
                  CONCAT(
                      '지급단계 ', cc.payment_stage,
                      ' · 한도액 ', TO_CHAR(cc.limit_amount, 'FM999,999,999,990'), '원',
                      ' · 산입액 ', TO_CHAR(cc.included_amount, 'FM999,999,999,990'), '원',
                      CASE WHEN cc.usage_pct IS NOT NULL
                           THEN CONCAT(' · 사용률 ', cc.usage_pct, '%') ELSE '' END
                  ),
                  jsonb_build_object(
                      'capCheckId', cc.cap_check_id,
                      'paymentStage', cc.payment_stage,
                      'limitAmount', cc.limit_amount,
                      'includedAmount', cc.included_amount,
                      'remainingAmount', cc.remaining_amount,
                      'usagePct', cc.usage_pct,
                      'resultStatus', cc.result_status,
                      'calculationSnapshot', cc.calculation_snapshot
                  )
              ) recorded
             WHERE cc.validation_run_id = :validationRunId
               AND cc.result_status IN ('VIOLATION', 'REVIEW_REQUIRED')
            """, nativeQuery = true)
    long insertFromCapChecks(@Param("validationRunId") Long validationRunId);

    /**
     * 월 검증 8단계: 이번 실행의 차익거래 검토대상·검토필요 전부를 한 번에 탐지·기록한다.
     * ARBITRAGE_CANDIDATE는 이유가 하나뿐이라 키에 reason 세그먼트가 없고(V24 보정과
     * 일치), 그 외 유형은 V23_1 레거시 재계산(끝에 :reason_code)과 같은 형식을 쓴다.
     */
    @Query(value = """
            SELECT COUNT(*) FILTER (WHERE recorded.new_case)
              FROM (
                  SELECT ac.*,
                         CASE
                             WHEN ac.result_status = 'CANDIDATE' THEN 'ARBITRAGE_CANDIDATE'
                             WHEN COALESCE(ac.calculation_snapshot ->> 'decisionReason', '')
                                      LIKE '%환급률표 후보가 없습니다%'
                               OR COALESCE(ac.calculation_snapshot ->> 'decisionReason', '')
                                      LIKE '%환급률표 ID가 없습니다%'
                               OR COALESCE(ac.calculation_snapshot ->> 'decisionReason', '')
                                      LIKE '%해약환급률표가 없습니다%'
                                 THEN 'REFUND_TABLE_MISSING'
                             WHEN COALESCE(ac.calculation_snapshot ->> 'decisionReason', '') LIKE '%상품코드%'
                               OR COALESCE(ac.calculation_snapshot ->> 'decisionReason', '')
                                      LIKE '%환급률표가 일치하지 않습니다%'
                                 THEN 'PRODUCT_CODE_MISMATCH'
                             WHEN COALESCE(ac.calculation_snapshot ->> 'decisionReason', '') LIKE '%여러 건%'
                                 THEN 'POLICY_DUPLICATE'
                             ELSE 'DATA_QUALITY'
                         END AS detected_type,
                         CASE
                             WHEN ac.result_status = 'CANDIDATE' THEN 'ARBITRAGE_LIMIT_EXCEEDED'
                             WHEN COALESCE(ac.calculation_snapshot ->> 'decisionReason', '')
                                      LIKE '%금융 스냅샷이 없습니다%'
                                 THEN 'FINANCIAL_SNAPSHOT_MISSING'
                             WHEN COALESCE(ac.calculation_snapshot ->> 'decisionReason', '') LIKE '%환급률표%없습니다%'
                               OR COALESCE(ac.calculation_snapshot ->> 'decisionReason', '')
                                      LIKE '%환급률표 ID가 없습니다%'
                                 THEN 'REFUND_TABLE_MISSING'
                             WHEN COALESCE(ac.calculation_snapshot ->> 'decisionReason', '') LIKE '%상품코드%'
                               OR COALESCE(ac.calculation_snapshot ->> 'decisionReason', '')
                                      LIKE '%환급률표가 일치하지 않습니다%'
                                 THEN 'PRODUCT_CODE_MISMATCH'
                             WHEN COALESCE(ac.calculation_snapshot ->> 'decisionReason', '') LIKE '%여러 건%'
                                 THEN 'POLICY_DUPLICATE'
                             ELSE 'ARBITRAGE_DATA_REVIEW_REQUIRED'
                         END AS detected_reason
                    FROM fgc.arbitrage_check ac
                   WHERE ac.validation_run_id = :validationRunId
                     AND ac.result_status IN ('CANDIDATE', 'REVIEW_REQUIRED')
              ) detected
              JOIN fgc.validation_run vr ON vr.validation_run_id = detected.validation_run_id
              CROSS JOIN LATERAL fgc.record_exception_detection(
                  CONCAT(detected.detected_type, ':', TO_CHAR(vr.validation_month, 'YYYY-MM'),
                         ':CONTRACT:', detected.contract_id, ':', detected.payment_stage,
                         CASE WHEN detected.detected_type = 'ARBITRAGE_CANDIDATE'
                              THEN '' ELSE CONCAT(':', detected.detected_reason) END),
                  detected.detected_type,
                  detected.detected_reason,
                  CASE detected.result_status WHEN 'CANDIDATE' THEN 'HIGH' ELSE 'WARNING' END,
                  detected.validation_run_id, detected.contract_id, NULL, NULL,
                  'ARBITRAGE_CHECK', CAST(detected.arbitrage_check_id AS varchar),
                  CASE
                      WHEN detected.result_status = 'CANDIDATE'
                           AND detected.net_difference_amount IS NOT NULL THEN CONCAT(
                          '차익거래 검토대상 — 순차액 ',
                          TO_CHAR(detected.net_difference_amount, 'FM999,999,999,990'), '원')
                      WHEN detected.result_status = 'CANDIDATE' THEN '차익거래 검토대상'
                      ELSE '차익거래 검증 자료 확인 필요'
                  END,
                  COALESCE(
                      detected.calculation_snapshot ->> 'decisionReason',
                      CONCAT('지급단계 ', detected.payment_stage, ' · 순차액 ',
                             TO_CHAR(COALESCE(detected.net_difference_amount, 0), 'FM999,999,999,990'), '원')
                  ),
                  jsonb_build_object(
                      'arbitrageCheckId', detected.arbitrage_check_id,
                      'paymentStage', detected.payment_stage,
                      'asOfDate', detected.as_of_date,
                      'resultStatus', detected.result_status,
                      'netDifferenceAmount', detected.net_difference_amount,
                      'calculationSnapshot', detected.calculation_snapshot
                  )
              ) recorded
            """, nativeQuery = true)
    long insertFromArbitrageChecks(@Param("validationRunId") Long validationRunId);

    /** 월 검증 8단계: 이번 실행의 대사 불일치(MATCHED 제외) 전부를 한 번에 탐지·기록한다. */
    @Query(value = """
            SELECT COUNT(*) FILTER (WHERE recorded.new_case)
              FROM fgc.reconciliation_result rr
              JOIN fgc.reconciliation_run rrn
                ON rrn.reconciliation_run_id = rr.reconciliation_run_id
              JOIN fgc.validation_run vr ON vr.validation_run_id = rrn.validation_run_id
              CROSS JOIN LATERAL fgc.record_exception_detection(
                  CONCAT('RECONCILIATION_MISMATCH:', TO_CHAR(vr.validation_month, 'YYYY-MM'),
                         ':CONTRACT:', rr.contract_id, ':', rrn.payment_stage, ':',
                         LEFT(rr.match_group_key, 140), ':', MD5(rr.match_group_key), ':',
                         COALESCE(rr.primary_reason_code, rr.result_type, 'RECONCILIATION_MISMATCH')),
                  'RECONCILIATION_MISMATCH',
                  COALESCE(rr.primary_reason_code, rr.result_type, 'RECONCILIATION_MISMATCH'),
                  CASE rr.result_type WHEN 'REVIEW_REQUIRED' THEN 'WARNING' ELSE 'HIGH' END,
                  rrn.validation_run_id, rr.contract_id,
                  COALESCE(rr.actual_agent_id, rr.expected_agent_id), rrn.tolerance_policy_version_id,
                  'RECONCILIATION_RESULT', CAST(rr.reconciliation_result_id AS varchar),
                  CASE COALESCE(rr.primary_reason_code, rr.result_type)
                      WHEN 'ACTUAL_MISSING' THEN CONCAT(
                          '실제 지급 없음 — 예상 ',
                          TO_CHAR(COALESCE(rr.expected_total_amount, 0), 'FM999,999,999,990'),
                          '원 지급 건 미발견')
                      WHEN 'EXPECTED_MISSING' THEN CONCAT(
                          '예상 지급 없음 — 실제 ',
                          TO_CHAR(COALESCE(rr.actual_total_amount, 0), 'FM999,999,999,990'),
                          '원 대응 예상 건 없음')
                      WHEN 'AMOUNT_DIFFERENCE' THEN CONCAT(
                          '금액 차이 ', TO_CHAR(ABS(COALESCE(rr.difference_amount, 0)), 'FM999,999,999,990'),
                          '원 — 예상 ', TO_CHAR(COALESCE(rr.expected_total_amount, 0), 'FM999,999,999,990'),
                          ' / 실제 ', TO_CHAR(COALESCE(rr.actual_total_amount, 0), 'FM999,999,999,990'))
                      WHEN 'DUPLICATE' THEN CONCAT(
                          '대사 대상 중복 — 차액 ',
                          TO_CHAR(COALESCE(rr.difference_amount, 0), 'FM999,999,999,990'), '원')
                      ELSE CONCAT(
                          '대사 불일치 — 차액 ',
                          TO_CHAR(COALESCE(rr.difference_amount, 0), 'FM999,999,999,990'), '원')
                  END,
                  CONCAT(
                      '지급단계 ', rrn.payment_stage,
                      ' · 예상 ', TO_CHAR(COALESCE(rr.expected_total_amount, 0), 'FM999,999,999,990'), '원',
                      ' / 실제 ', TO_CHAR(COALESCE(rr.actual_total_amount, 0), 'FM999,999,999,990'), '원',
                      ' · 차액 ', TO_CHAR(COALESCE(rr.difference_amount, 0), 'FM999,999,999,990'), '원'
                  ),
                  jsonb_build_object(
                      'reconciliationResultId', rr.reconciliation_result_id,
                      'paymentStage', rrn.payment_stage,
                      'matchGroupKey', rr.match_group_key,
                      'resultType', rr.result_type,
                      'primaryReasonCode', rr.primary_reason_code,
                      'secondaryReasonCodes', rr.secondary_reason_codes,
                      'expectedTotalAmount', rr.expected_total_amount,
                      'actualTotalAmount', rr.actual_total_amount,
                      'differenceAmount', rr.difference_amount
                  )
              ) recorded
             WHERE rrn.validation_run_id = :validationRunId
               AND rr.result_type <> 'MATCHED'
            """, nativeQuery = true)
    long insertFromReconciliationResults(@Param("validationRunId") Long validationRunId);

    /**
     * 월 검증 8단계: 이번 실행에서 생성된 검증원장의 차변·대변 불균형 전부를 한 번에
     * 탐지·기록한다. 분개 원천 식별자(source_entity_id)까지 넣어야 같은 실행의 서로 다른
     * 불균형 분개가 별도 업무건으로 남는다 — 원천은 실행이 바뀌어도 안정적이다(V29).
     */
    @Query(value = """
            SELECT COUNT(*) FILTER (WHERE recorded.new_case)
              FROM fgc.vw_journal_imbalance imbalance
              JOIN fgc.journal_header header
                ON header.journal_header_id = imbalance.journal_header_id
              JOIN fgc.validation_run vr ON vr.validation_run_id = header.validation_run_id
              CROSS JOIN LATERAL fgc.record_exception_detection(
                  CONCAT('JOURNAL_IMBALANCE:', TO_CHAR(vr.validation_month, 'YYYY-MM'),
                         ':CONTRACT:', COALESCE(CAST(header.contract_id AS varchar), '-'), ':',
                         header.journal_type, ':', header.source_entity_type, ':',
                         header.source_entity_id),
                  'JOURNAL_IMBALANCE', 'JOURNAL_IMBALANCE', 'CRITICAL',
                  header.validation_run_id, header.contract_id, NULL, NULL,
                  'JOURNAL_HEADER', CAST(imbalance.journal_header_id AS varchar),
                  CONCAT('검증원장 차변·대변 불균형 — 차액 ',
                         TO_CHAR(COALESCE(imbalance.difference_amount, 0), 'FM999,999,999,990'), '원'),
                  CONCAT(
                      '분개번호 ', imbalance.journal_no,
                      ' · 차변 ', TO_CHAR(COALESCE(imbalance.debit_total, 0), 'FM999,999,999,990'), '원',
                      ' / 대변 ', TO_CHAR(COALESCE(imbalance.credit_total, 0), 'FM999,999,999,990'), '원',
                      ' · 차액 ', TO_CHAR(COALESCE(imbalance.difference_amount, 0), 'FM999,999,999,990'), '원'
                  ),
                  jsonb_build_object(
                      'journalHeaderId', imbalance.journal_header_id,
                      'journalNo', imbalance.journal_no,
                      'journalType', header.journal_type,
                      'debitTotal', imbalance.debit_total,
                      'creditTotal', imbalance.credit_total,
                      'differenceAmount', imbalance.difference_amount
                  )
              ) recorded
             WHERE header.validation_run_id = :validationRunId
            """, nativeQuery = true)
    long insertFromJournalImbalances(@Param("validationRunId") Long validationRunId);
}
