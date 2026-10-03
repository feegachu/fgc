package com.susukkang.fgc.validation.repository;

import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.validation.dto.ValidationRunListRow;
import com.susukkang.fgc.validation.entity.ValidationRun;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
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
public interface ValidationRunRepository extends JpaRepository<ValidationRun, Long> {

    /** IF-API-51 확정 트랜잭션 직렬화용 행 잠금 조회. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM ValidationRun v WHERE v.validationRunId = :validationRunId")
    Optional<ValidationRun> findByIdForUpdate(@Param("validationRunId") Long validationRunId);

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

    /**
     * DailyChangedContractJob "하루 1건" 규칙 지원용 — [dayStart, dayEnd) 구간의 최근(run_no 최대) 1건.
     * "First"+"OrderBy...Desc" 파생 쿼리로 LIMIT 1을 적용한다 — @Query로 직접 쓰면 JPQL이
     * LIMIT을 지원하지 않아 구간에 행이 2건 이상일 때 NonUniqueResultException이 난다.
     */
    Optional<ValidationRun> findFirstByRunTypeAndCreatedAtBetweenOrderByRunNoDesc(
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
}
