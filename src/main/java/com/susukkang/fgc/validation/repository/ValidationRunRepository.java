package com.susukkang.fgc.validation.repository;

import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.validation.dto.AgentCapMonitoringRow;
import com.susukkang.fgc.validation.dto.FinalizeChecklistCounts;
import com.susukkang.fgc.validation.dto.FinalizedValidationRunRow;
import com.susukkang.fgc.validation.dto.ValidationRunListRow;
import com.susukkang.fgc.validation.dto.ValidationRunResultSummaryRow;
import com.susukkang.fgc.validation.entity.ValidationRun;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 설명 : validation_run 공유 Repository. #379가 제공하고 #371·#373·#377·#378·#380이
 * 소비하는 생성·조회·잠금·상태전이 API.
 *
 * {@code @Modifying} 조건부 UPDATE는 전부 {@code clearAutomatically = true}다 — 같은
 * 트랜잭션 안에서 뒤이어 {@code findById}로 재조회하는 호출부(전이/확정 서비스)가 영속성
 * 컨텍스트 1차 캐시에서 갱신 전 엔티티를 그대로 돌려받는 사고를 막기 위함이다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-10-03
 */
public interface ValidationRunRepository extends JpaRepository<ValidationRun, Long>, ValidationRunSearch {

    /** 비동기 재시작 대기 중의 이전 FAILED와 새 시도 결과를 구별하는 추적 ID다. */
    @Query(value = """
            SELECT request_id FROM fgc.audit_log
             WHERE entity_type='VALIDATION_RUN' AND entity_id=CAST(:validationRunId AS varchar)
               AND action_code IN ('VALIDATION_RUN_STARTED', 'VALIDATION_RUN_RETRIED')
             ORDER BY audit_log_id DESC LIMIT 1
            """, nativeQuery = true)
    String findLatestAttemptRequestId(@Param("validationRunId") Long validationRunId);

    /**
     * 검증월 단위 트랜잭션 advisory lock(#491). 커밋·롤백 때 자동으로 풀린다.
     *
     * <p>확정 조건 7("같은 검증월의 가장 최근 실행인가")은 check-then-act라, 실행 행 잠금만으로는
     * 조회 직후 같은 달 새 실행이 INSERT·커밋되는 경합을 막지 못한다(새 행 INSERT는 기존 행을 잠그지 않음).
     * 그래서 실행 생성({@code ValidationRunCreateServiceImpl#attemptCreate})과 확정
     * ({@code ValidationRunFinalizationServiceImpl#finalizeRun})이 같은 키를 이 메서드로 잡는다.
     * 키를 만드는 곳을 여기 하나로 둔 이유다 — 두 쪽 키가 어긋나면 잠금이 조용히 무력화된다.
     *
     * <p>잠금 순서: 확정은 실행 행 → 검증월, 생성은 검증월만 잡는다. 생성 쪽이 기존 실행 행을 잠그지
     * 않으므로 순환 대기(교착)가 생기지 않는다. 키는 {@code hashtextextended}(64비트) 해시라 다른 기능의
     * 키(예: 전표 채번)와 충돌할 확률은 무시할 수 있고, 충돌해도 잠깐 기다릴 뿐 결과는 틀리지 않는다.
     *
     * <p>이 잠금을 잡은 트랜잭션 안에서 {@code ValidationRunCreateService#create}를 부르지 않는다.
     * create는 REQUIRES_NEW 새 트랜잭션에서 같은 키를 다시 잡으므로, 바깥 트랜잭션이 끝나기를 영원히
     * 기다린다(PostgreSQL이 감지하지 못하는 앱 수준 자기 교착). 2026-10-11 기준 그런 호출 경로는 없다.
     *
     * <p>키 문자열은 Java에서 만든다({@link LocalDate#toString()} = ISO {@code 2026-07-01}). SQL에서
     * {@code CAST(:date AS text)}로 만들면 드라이버가 date·timestamp 중 무엇으로 바인딩하느냐에 따라
     * 문자열이 달라져, 같은 달인데 다른 키가 될 수 있다.
     */
    default void lockValidationMonth(LocalDate validationMonth) {
        lockAdvisoryKey(VALIDATION_MONTH_LOCK_PREFIX + validationMonth);
    }

    /** {@link #lockValidationMonth} 키 접두사. 다른 기능의 advisory 키와 섞이지 않게 네임스페이스를 둔다. */
    String VALIDATION_MONTH_LOCK_PREFIX = "fgc.validation_run.month:";

    /** 문자열 키의 트랜잭션 advisory lock. 직접 쓰지 말고 {@link #lockValidationMonth}를 쓴다. */
    @Transactional(propagation = Propagation.MANDATORY)
    @NativeQuery("""
            SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:lockKey, 0))
            """)
    Integer lockAdvisoryKey(@Param("lockKey") String lockKey);

    /** IF-API-51 확정 트랜잭션 직렬화용 행 잠금 조회. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM ValidationRun v WHERE v.validationRunId = :validationRunId")
    Optional<ValidationRun> findByIdForUpdate(@Param("validationRunId") Long validationRunId);

    /** 대사 생성은 영속성 컨텍스트의 캐시 대신 잠근 DB 행의 현재 상태·월을 검증한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
            SELECT validation_month AS "validationMonth", status
              FROM fgc.validation_run WHERE validation_run_id = :validationRunId FOR UPDATE
            """, nativeQuery = true)
    Optional<LockedReference> findReferenceForUpdate(@Param("validationRunId") Long validationRunId);

    interface LockedReference {
        LocalDate getValidationMonth();
        String getStatus();
    }

    /** (validationMonth, runNo) 복합 유니크 키 조회 — 명시적 runNo 재시작의 멱등성 판단용. */
    Optional<ValidationRun> findByValidationMonthAndRunNo(LocalDate validationMonth, Integer runNo);

    /** validationMonth에 이미 있는 실행들 중 최대 run_no + 1. 하나도 없으면 1. */
    @Query("SELECT COALESCE(MAX(v.runNo), 0) + 1 FROM ValidationRun v WHERE v.validationMonth = :validationMonth")
    Integer findNextRunNo(@Param("validationMonth") LocalDate validationMonth);

    /** validationMonth에 runType='MONTHLY'이고 status가 활성(CREATED/RUNNING)인 실행이 있는지. */
    boolean existsByValidationMonthAndRunTypeAndStatusIn(
            LocalDate validationMonth, String runType, Collection<ValidationRunStatus> statuses);

    /**
     * runType='MANUAL_CONTRACT'이고 status가 활성인 실행이 있는지.
     * uq_validation_run_active_manual_contract(V13)의 애플리케이션측 사전 확인용 — month로 좁히지 않는다.
     */
    boolean existsByRunTypeAndStatusIn(String runType, Collection<ValidationRunStatus> statuses);

    /**
     * FGC-FUN-041 목록 조회. month/status는 null이면 그 조건을 걸지 않는다.
     * Pageable에 Sort를 주지 않아야 한다 — 쿼리 자체에 고정 ORDER BY가 있어 동적 정렬과
     * 충돌한다. 목록/전체건수를 Page 하나로 묶어 기존 search+count 두 메서드를 대체한다.
     */
    @Query(value = """
            SELECT new com.susukkang.fgc.validation.dto.ValidationRunListRow(
                v.validationRunId, v.validationMonth, v.runNo, v.runType,
                CAST(v.status AS string), v.currentStep,
                triggered.loginId, v.startedAt, v.completedAt,
                finalized.loginId, v.finalizedAt, v.failureMessage)
            FROM ValidationRun v
            LEFT JOIN AppUser triggered ON triggered.userId = v.triggeredBy
            LEFT JOIN AppUser finalized ON finalized.userId = v.finalizedBy
            WHERE (CAST(:month AS LocalDate) IS NULL OR v.validationMonth = :month)
              AND (:status IS NULL OR v.status = :status)
            ORDER BY v.validationMonth DESC, v.runNo DESC
            """,
            countQuery = """
            SELECT COUNT(v) FROM ValidationRun v
            WHERE (CAST(:month AS LocalDate) IS NULL OR v.validationMonth = :month)
              AND (:status IS NULL OR v.status = :status)
            """)
    Page<ValidationRunListRow> search(@Param("month") LocalDate month,
                                       @Param("status") ValidationRunStatus status,
                                       Pageable pageable);

    /** IF-API-47 상세 화면 헤더 — search와 같은 app_user 조인(login_id)을 id 조건으로. */
    @Query("""
            SELECT new com.susukkang.fgc.validation.dto.ValidationRunListRow(
                v.validationRunId, v.validationMonth, v.runNo, v.runType,
                CAST(v.status AS string), v.currentStep,
                triggered.loginId, v.startedAt, v.completedAt,
                finalized.loginId, v.finalizedAt, v.failureMessage)
            FROM ValidationRun v
            LEFT JOIN AppUser triggered ON triggered.userId = v.triggeredBy
            LEFT JOIN AppUser finalized ON finalized.userId = v.finalizedBy
            WHERE v.validationRunId = :validationRunId
            """)
    Optional<ValidationRunListRow> findHeaderById(@Param("validationRunId") Long validationRunId);

    /**
     * DailyChangedContractJob "하루 1건" 규칙 지원용 — [dayStart, dayEnd) 구간의 최근(run_no 최대) 1건.
     * "First"+"OrderBy...Desc" 파생 쿼리로 LIMIT 1을 적용한다. Between은 양끝 포함이라 쓰지 않는다(다음 날 00:00 정각 행이 오늘 것으로 잡힘). @Query로 직접 쓰면 JPQL이
     * LIMIT을 지원하지 않아 구간에 행이 2건 이상일 때 NonUniqueResultException이 난다.
     */
    Optional<ValidationRun> findFirstByRunTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByRunNoDesc(
            String runType, OffsetDateTime dayStart, OffsetDateTime dayEnd);

    /**
     * 동시 요청 하나만 성공시키는 조건부 상태 전이(낙관적 락과 같은 효과).
     * 허용된 전이인지(ValidationRunStatus.canTransitionTo)는 이 메서드가 아니라 서비스가 판단한다.
     *
     * @return 반영된 행 수. 0이면 미존재/상태충돌 둘 다 가능 — 구분은 호출부가 findById로 재조회.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE ValidationRun v SET v.status = :newStatus
             WHERE v.validationRunId = :validationRunId AND v.status = :expectedStatus
            """)
    int updateStatusIfCurrent(@Param("validationRunId") Long validationRunId,
                               @Param("expectedStatus") ValidationRunStatus expectedStatus,
                               @Param("newStatus") ValidationRunStatus newStatus);

    /** 배치 진행 기록 1/4: CREATED → RUNNING, current_step=1, started_at=now(). */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE ValidationRun v
               SET v.status = :running, v.currentStep = 1, v.startedAt = CURRENT_TIMESTAMP
             WHERE v.validationRunId = :validationRunId AND v.status = :created
            """)
    int transitionToRunning(@Param("validationRunId") Long validationRunId,
                             @Param("created") ValidationRunStatus created,
                             @Param("running") ValidationRunStatus running);

    /** 재시작은 실패 단계·기존 시작 시각을 보존하고 동시 요청 한 건만 점유한다. */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE ValidationRun v SET v.status = :running, v.completedAt = NULL, v.failureMessage = NULL
             WHERE v.validationRunId = :validationRunId AND v.status = :failed
            """)
    int restartFailed(@Param("validationRunId") Long validationRunId,
                      @Param("failed") ValidationRunStatus failed,
                      @Param("running") ValidationRunStatus running);

    /**
     * 배치 진행 기록 2/4: RUNNING 상태에서 current_step만 전진.
     * {@code currentStep <= :currentStep} 조건은 역행 방지 — journalPosting/imbalanceCheck가
     * 같은 stepNo=6을 공유해 같은 값을 두 번 쓰는 것은 정상이라 "<="(not "<")을 쓴다.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE ValidationRun v SET v.currentStep = :currentStep
             WHERE v.validationRunId = :validationRunId
               AND v.status = :running
               AND v.currentStep <= :currentStep
            """)
    int updateCurrentStep(@Param("validationRunId") Long validationRunId,
                           @Param("currentStep") Integer currentStep,
                           @Param("running") ValidationRunStatus running);

    /** 배치 진행 기록 3/4: 8단계까지 성공 후 RUNNING → COMPLETED. */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE ValidationRun v
               SET v.status = :completed, v.currentStep = 8, v.completedAt = CURRENT_TIMESTAMP
             WHERE v.validationRunId = :validationRunId AND v.status = :running
            """)
    int transitionToCompleted(@Param("validationRunId") Long validationRunId,
                               @Param("running") ValidationRunStatus running,
                               @Param("completed") ValidationRunStatus completed);

    /** 배치 진행 기록 4/4: 임의 Step 실패 시 RUNNING → FAILED, 실패 Step·사유 기록. */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE ValidationRun v
               SET v.status = :failed, v.currentStep = :currentStep, v.failureMessage = :failureMessage
             WHERE v.validationRunId = :validationRunId
               AND v.status = :running
               AND v.currentStep <= :currentStep
            """)
    int transitionToFailed(@Param("validationRunId") Long validationRunId,
                            @Param("currentStep") Integer currentStep,
                            @Param("failureMessage") String failureMessage,
                            @Param("running") ValidationRunStatus running,
                            @Param("failed") ValidationRunStatus failed);

    /** 멱등키가 다른 실행에서 이미 쓰였는지 사전 확인. */
    @Query("SELECT v.validationRunId FROM ValidationRun v WHERE v.finalizeIdempotencyKey = :idempotencyKey")
    Optional<Long> findValidationRunIdByFinalizeIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    /**
     * 상태·단계를 WHERE에 함께 둬 서비스 사전조회 이후의 경합도 최종 차단.
     * COMPLETED/8 → FINALIZED/10 전이와 확정 메타데이터 기록을 한 UPDATE로 원자화한다.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE ValidationRun v
               SET v.status = :finalized, v.currentStep = 10, v.finalizedAt = CURRENT_TIMESTAMP,
                   v.finalizedBy = :finalizedBy, v.finalizeIdempotencyKey = :idempotencyKey
             WHERE v.validationRunId = :validationRunId
               AND v.status = :completed
               AND v.currentStep = 8
            """)
    int finalizeIfCompleted(@Param("validationRunId") Long validationRunId,
                             @Param("finalizedBy") Long finalizedBy,
                             @Param("idempotencyKey") String idempotencyKey,
                             @Param("completed") ValidationRunStatus completed,
                             @Param("finalized") ValidationRunStatus finalized);

    /**
     * IF-API-50 / FUN-044-01. 한 네이티브 쿼리의 MVCC 스냅샷에서 여덟 확정 조건의 실패
     * 건수를 함께 읽어 조건별 조회 시점 차이를 없앤다. 전표·한도는 validation_run_id 범위,
     * 예외·귀속합계는 문서 정의대로 validation_month 범위다(#330 — 예외를 실행 범위로
     * 좁히면 실시간 경로의 CRITICAL·POLICY_*가 빠진 채 확정됨). 집계가 여러 이미 JPA로
     * 전환된 테이블(journal_header, exception_case, cap_check 등)을 넘나드는 다중
     * 서브쿼리라 JPQL 대신 네이티브 SQL + {@code @SqlResultSetMapping}(ValidationRun
     * 엔티티에 선언)으로 유지한다.
     *
     * <p>조건 7(#491): 같은 검증월에 이 실행보다 뒤에 만든 MONTHLY·PRE_CONFIRM 실행이 있으면 확정하지 않는다.
     * 순서는 IDENTITY인 validation_run_id로 정하고(run_no는 명시 채번 가능), 뒤 실행의 상태는 가리지 않는다
     * (CREATED·RUNNING·FAILED여도 다시 돌리려 했다는 뜻). MANUAL_CONTRACT는 매일 생기는 일일 배치라 제외한다.
     * {@code run_type IN ('MONTHLY','PRE_CONFIRM')}은 {@code ValidationRunType#isFinalizable}과 같은 집합이다(#494).
     *
     * <p>조건 8(#494): 같은 검증월에 이미 FINALIZED 실행이 있으면 확정하지 않는다 — 확정은 그 달을 대표하는 한 건이다.
     * 실행 생성은 막지 않는다: FUN-044 인수조건 "동일 월 재검증은 새 실행번호로 생성되어 이전 결과와 비교할 수 있다".
     * 유형은 가리지 않는다(#494 이전에 확정된 MANUAL_CONTRACT 도 그 달의 확정으로 본다). 확정 경로는 검증월 잠금을
     * 잡은 뒤 이 값을 세므로, 같은 달 두 실행을 동시에 확정해도 두 번째는 첫 번째의 FINALIZED 를 보고 멈춘다.
     */
    @NativeQuery(value = """
            SELECT vr.validation_run_id AS validationRunId,
                   vr.validation_month AS validationMonth,
                   CASE WHEN vr.status = 'COMPLETED' AND vr.current_step = 8 THEN 0 ELSE 1 END
                       AS incompleteRunCount,
                   (SELECT COUNT(*)
                      FROM fgc.vw_journal_imbalance imbalance
                      JOIN fgc.journal_header header
                        ON header.journal_header_id = imbalance.journal_header_id
                     WHERE header.validation_run_id = vr.validation_run_id)
                       AS journalImbalanceCount,
                   (SELECT COUNT(*)
                      FROM fgc.exception_case exception
                     WHERE exception.validation_month = vr.validation_month
                       AND exception.severity = 'CRITICAL'
                       AND exception.status IN ('NEW', 'IN_REVIEW'))
                       AS unresolvedCriticalExceptionCount,
                   (SELECT COUNT(*)
                      FROM fgc.exception_case exception
                     WHERE exception.validation_month = vr.validation_month
                       AND exception.exception_type IN ('POLICY_MISSING', 'POLICY_DUPLICATE')
                       AND exception.status IN ('NEW', 'IN_REVIEW'))
                       AS unresolvedPolicyExceptionCount,
                   (SELECT COUNT(*)
                      FROM fgc.vw_transaction_attribution_balance balance
                      JOIN fgc.commission_transaction ct
                        ON ct.commission_transaction_id = balance.commission_transaction_id
                     WHERE ct.settlement_month = vr.validation_month)
                       AS attributionImbalanceCount,
                   (SELECT COUNT(*)
                      FROM (
                           SELECT cc.cap_check_id
                             FROM fgc.cap_check cc
                             LEFT JOIN fgc.cap_check_detail detail
                               ON detail.cap_check_id = cc.cap_check_id
                              AND detail.classification_snapshot = 'INCLUDED'
                            WHERE cc.validation_run_id = vr.validation_run_id
                            GROUP BY cc.cap_check_id, cc.included_amount
                           HAVING cc.included_amount <> COALESCE(SUM(ROUND(detail.amount, 0)), 0)
                      ) mismatch)
                       AS capDetailMismatchCount,
                   (SELECT COUNT(*)
                      FROM fgc.validation_run newer
                     WHERE newer.validation_month = vr.validation_month
                       AND newer.run_type IN ('MONTHLY', 'PRE_CONFIRM')
                       AND newer.validation_run_id > vr.validation_run_id)
                       AS supersededRunCount,
                   (SELECT MAX(newer.validation_run_id)
                      FROM fgc.validation_run newer
                     WHERE newer.validation_month = vr.validation_month
                       AND newer.run_type IN ('MONTHLY', 'PRE_CONFIRM')
                       AND newer.validation_run_id > vr.validation_run_id)
                       AS latestRunId,
                   (SELECT COUNT(*)
                      FROM fgc.validation_run finalized
                     WHERE finalized.validation_month = vr.validation_month
                       AND finalized.status = 'FINALIZED'
                       AND finalized.validation_run_id <> vr.validation_run_id)
                       AS finalizedRunCount,
                   (SELECT MAX(finalized.validation_run_id)
                      FROM fgc.validation_run finalized
                     WHERE finalized.validation_month = vr.validation_month
                       AND finalized.status = 'FINALIZED'
                       AND finalized.validation_run_id <> vr.validation_run_id)
                       AS finalizedRunId
              FROM fgc.validation_run vr
             WHERE vr.validation_run_id = :validationRunId
            """, sqlResultSetMapping = "FinalizeChecklistCountsMapping")
    Optional<FinalizeChecklistCounts> findFinalizeChecklistCounts(@Param("validationRunId") Long validationRunId);

    /** 확정 응답 재조회용. finalizedBy는 내부 user_id가 아니라 화면 표시용 login_id를 반환한다. */
    @Query("""
            SELECT new com.susukkang.fgc.validation.dto.FinalizedValidationRunRow(
                v.validationRunId, CAST(v.status AS string), v.finalizedAt, u.loginId, v.finalizeIdempotencyKey)
            FROM ValidationRun v
            LEFT JOIN AppUser u ON u.userId = v.finalizedBy
            WHERE v.validationRunId = :validationRunId
            """)
    Optional<FinalizedValidationRunRow> findFinalizationById(@Param("validationRunId") Long validationRunId);

    /**
     * VRUN-W02 ③건수·④결과 요약 4블록. 결과 테이블 5곳(validation_target, cap_check,
     * arbitrage_check, journal_header+vw_journal_imbalance, reconciliation_run+result,
     * schedule_header, exception_occurrence+exception_case)을 validation_run_id로 스코프해
     * 한 번에 센다. arbitrage_check·reconciliation_*·schedule_header·exception_*는 아직
     * JPA 엔티티가 없는(#377·#378·#380 소유) 테이블이라 JPQL로 옮길 수 없어 네이티브 SQL +
     * {@code @SqlResultSetMapping}(ValidationRun 엔티티)으로 원본 SQL을 그대로 유지한다.
     * GROUP BY 없는 집계라 결과가 없는 실행도 0이 채워진 1행이 나온다.
     */
    @NativeQuery(value = """
            SELECT vt.targetSelectedCount, vt.targetExcludedCount, vt.targetReviewRequiredCount,
                   cc.capCheckedCount, cc.capViolationCount, cc.capWarningCount, cc.capReviewRequiredCount,
                   ac.arbitrageCheckedCount, ac.arbitrageCandidateCount, ac.arbitrageReviewRequiredCount,
                   jh.journalCount, jh.journalImbalanceCount,
                   rr.reconciliationResultCount, rr.reconciliationMismatchCount,
                   sh.scheduleGeneratedCount,
                   rr.reconciliationMatchedCount, rr.reconciliationMismatchedCount,
                   rr.reconciliationUnmatchedCount, rr.reconciliationDifferenceAmountTotal,
                   ex.exceptionDetectedCount, ex.exceptionNewCount, ex.exceptionRecurringCount,
                   ex.exceptionReopenedCount, ex.exceptionNotDetectedCount,
                   ex.exceptionOpenWorkItemCount, ex.finalizedResultDiffCount,
                   -- #498 비교 기준: ExceptionCaseRepository#insertFromFinalizedCapResultDiffs 와 같은 조건을 유지한다
                   (SELECT MAX(finalized.validation_run_id)
                      FROM fgc.validation_run finalized
                      JOIN fgc.validation_run target_run
                        ON target_run.validation_run_id = :validationRunId
                     WHERE finalized.validation_month = target_run.validation_month
                       AND finalized.status = 'FINALIZED'
                       AND finalized.validation_run_id < target_run.validation_run_id)
                       AS comparedFinalizedRunId
              FROM (SELECT COUNT(*) FILTER (WHERE selection_status = 'SELECTED')        AS targetSelectedCount,
                           COUNT(*) FILTER (WHERE selection_status = 'EXCLUDED')        AS targetExcludedCount,
                           COUNT(*) FILTER (WHERE selection_status = 'REVIEW_REQUIRED') AS targetReviewRequiredCount
                      FROM fgc.validation_target
                     WHERE validation_run_id = :validationRunId) vt
             CROSS JOIN (SELECT COUNT(*) FILTER (WHERE result_status <> 'NOT_APPLICABLE')   AS capCheckedCount,
                                COUNT(*) FILTER (WHERE result_status = 'VIOLATION')       AS capViolationCount,
                                COUNT(*) FILTER (WHERE result_status = 'WARNING')         AS capWarningCount,
                                COUNT(*) FILTER (WHERE result_status = 'REVIEW_REQUIRED') AS capReviewRequiredCount
                           FROM fgc.cap_check
                          WHERE validation_run_id = :validationRunId) cc
             CROSS JOIN (SELECT COUNT(*) FILTER (WHERE result_status <> 'NOT_APPLICABLE')   AS arbitrageCheckedCount,
                                COUNT(*) FILTER (WHERE result_status = 'CANDIDATE')       AS arbitrageCandidateCount,
                                COUNT(*) FILTER (WHERE result_status = 'REVIEW_REQUIRED') AS arbitrageReviewRequiredCount
                           FROM fgc.arbitrage_check
                          WHERE validation_run_id = :validationRunId) ac
             CROSS JOIN (SELECT COUNT(*)                     AS journalCount,
                                COUNT(vji.journal_header_id) AS journalImbalanceCount
                           FROM fgc.journal_header j
                           LEFT JOIN fgc.vw_journal_imbalance vji ON vji.journal_header_id = j.journal_header_id
                          WHERE j.validation_run_id = :validationRunId) jh
             CROSS JOIN (SELECT COUNT(r.reconciliation_result_id)                                    AS reconciliationResultCount,
                                COUNT(*) FILTER (WHERE r.result_type <> 'MATCHED')                   AS reconciliationMismatchCount,
                                COUNT(*) FILTER (WHERE r.result_type = 'MATCHED')                     AS reconciliationMatchedCount,
                                COUNT(*) FILTER (WHERE r.result_type NOT IN
                                    ('MATCHED', 'EXPECTED_MISSING', 'ACTUAL_MISSING'))                AS reconciliationMismatchedCount,
                                COUNT(*) FILTER (WHERE r.result_type IN
                                    ('EXPECTED_MISSING', 'ACTUAL_MISSING'))                            AS reconciliationUnmatchedCount,
                                COALESCE(SUM(ROUND(r.difference_amount, 0)) FILTER (WHERE r.result_type <> 'MATCHED'), 0)
                                                                                                        AS reconciliationDifferenceAmountTotal
                           FROM fgc.reconciliation_run recon
                           LEFT JOIN fgc.reconciliation_result r ON r.reconciliation_run_id = recon.reconciliation_run_id
                          WHERE recon.validation_run_id = :validationRunId AND NOT recon.superseded_yn) rr
             CROSS JOIN (SELECT COUNT(*) AS scheduleGeneratedCount
                           FROM fgc.schedule_header
                          WHERE validation_run_id = :validationRunId) sh
             CROSS JOIN (
                 SELECT COUNT(occurrence.exception_occurrence_id) AS exceptionDetectedCount,
                        COUNT(*) FILTER (WHERE occurrence.is_new_case) AS exceptionNewCount,
                        COUNT(*) FILTER (WHERE NOT occurrence.is_new_case) AS exceptionRecurringCount,
                        COUNT(*) FILTER (WHERE occurrence.was_reopened) AS exceptionReopenedCount,
                        COUNT(*) FILTER (WHERE occurrence.reason_code = 'FINALIZED_RESULT_DIFF')
                            AS finalizedResultDiffCount,
                        (SELECT COUNT(*)
                           FROM fgc.exception_case ec
                           JOIN fgc.validation_run target_run
                             ON target_run.validation_run_id = :validationRunId
                          WHERE ec.validation_month = target_run.validation_month
                            AND ec.status IN ('NEW', 'IN_REVIEW')
                            AND NOT EXISTS (
                                SELECT 1
                                  FROM fgc.exception_occurrence current_occurrence
                                 WHERE current_occurrence.exception_case_id = ec.exception_case_id
                                   AND current_occurrence.validation_run_id = :validationRunId
                            )) AS exceptionNotDetectedCount,
                        (SELECT COUNT(*)
                           FROM fgc.exception_case ec
                           JOIN fgc.validation_run target_run
                             ON target_run.validation_run_id = :validationRunId
                          WHERE ec.validation_month = target_run.validation_month
                            AND ec.status IN ('NEW', 'IN_REVIEW')) AS exceptionOpenWorkItemCount
                   FROM fgc.exception_occurrence occurrence
                  WHERE occurrence.validation_run_id = :validationRunId
             ) ex
            """, sqlResultSetMapping = "ValidationRunResultSummaryRowMapping")
    Optional<ValidationRunResultSummaryRow> summarize(@Param("validationRunId") Long validationRunId);

    /**
     * FGC-FUN-043 설계사 단위 1,200% 모니터링 참고용 집계. payment_stage로 반드시 나눈다 —
     * 원수사→GA와 GA→설계사 1,200%는 계산 규칙이 달라 합치면 안 된다. cap_check가
     * (validation_run_id, contract_id, payment_stage)로 유일해(uq_cap_check_monthly)
     * GROUP BY에서 빠지면 같은 계약의 두 지급단계 행이 하나로 합산된다.
     */
    @NativeQuery(value = """
            SELECT ag.agent_id                                                    AS agentId,
                   ag.agent_name                                                  AS agentName,
                   cc.payment_stage                                               AS paymentStage,
                   COUNT(*) FILTER (WHERE cc.result_status <> 'NOT_APPLICABLE')    AS checkedCount,
                   COUNT(*) FILTER (WHERE cc.result_status = 'VIOLATION')         AS violationCount,
                   COUNT(*) FILTER (WHERE cc.result_status = 'WARNING')           AS warningCount,
                   COUNT(*) FILTER (WHERE cc.result_status = 'REVIEW_REQUIRED')   AS reviewRequiredCount
              FROM fgc.cap_check cc
              JOIN fgc.insurance_contract ic ON ic.contract_id = cc.contract_id
              JOIN fgc.agent ag ON ag.agent_id = ic.agent_id
             WHERE cc.validation_run_id = :validationRunId
               AND cc.result_status <> 'NOT_APPLICABLE'
             GROUP BY ag.agent_id, ag.agent_name, cc.payment_stage
             ORDER BY ag.agent_id, cc.payment_stage
            """, sqlResultSetMapping = "AgentCapMonitoringRowMapping")
    List<AgentCapMonitoringRow> summarizeCapByAgent(@Param("validationRunId") Long validationRunId);
}
