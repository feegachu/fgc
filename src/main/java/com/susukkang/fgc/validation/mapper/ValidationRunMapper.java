package com.susukkang.fgc.validation.mapper;

import com.susukkang.fgc.validation.dto.ValidationRunInsertRow;
import com.susukkang.fgc.validation.dto.FinalizeChecklistCounts;
import com.susukkang.fgc.validation.dto.ValidationRunListRow;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface ValidationRunMapper {

    /** validation_run_id 단건 조회. 없으면 null. */
    ValidationRunRow findById(@Param("validationRunId") Long validationRunId);

    /** 운영정책서 제44조 여섯 확정 조건의 실패 건수를 한 스냅샷에서 조회한다. */
    FinalizeChecklistCounts findFinalizeChecklistCounts(@Param("validationRunId") Long validationRunId);

    /**
     * (validationMonth, runNo) 복합 유니크 키로 단건 조회. 없으면 null.
     * 배치가 명시적 runNo로 재시작할 때 이미 만든 행을 재사용할지 판단하는 용도(멱등성).
     */
    ValidationRunRow findByMonthAndRunNo(@Param("validationMonth") LocalDate validationMonth,
                                          @Param("runNo") int runNo);

    /**
     * validationMonth에 이미 있는 실행들 중 최대 run_no + 1. 하나도 없으면 1
     */
    Integer findNextRunNo(@Param("validationMonth") LocalDate validationMonth);

    /**
     * validationMonth에 run_type='MONTHLY'이고 status가 CREATED/RUNNING인(=활성) 실행이
     * 있는지
     */
    boolean existsActiveMonthlyRun(@Param("validationMonth") LocalDate validationMonth);

    /**
     * run_type='MANUAL_CONTRACT'이고 status가 CREATED/RUNNING인(=활성) 실행이 있는지.
     * uq_validation_run_active_manual_contract(V8)의 애플리케이션측 사전 확인용.
     */
    boolean existsActiveManualContractRun();

    /**
     * validation_run 1행 INSERT
     */
    void insert(ValidationRunInsertRow row);

    /**

     * 현재 상태(expectedStatus)를 조건으로 하는 조건부 UPDATE.
     * WHERE 절에 validation_run_id뿐 아니라 status = #{expectedStatus}까지 걸어야
     * 동시에 두 요청이 같은 실행을 전이시키려 할 때 하나만 성공(낙관적 락과 같은 효과).
     *
     * @return 반영된 행 수. 0이면 실행 미존재/상태 충돌 둘 다 가능
     * — 사전 조회 이후 삭제됐을 수도 있으니, 구분은 Service가 0건일 때 findById로 재조회해서 판단
     */
    int updateStatusIfCurrent(@Param("validationRunId") Long validationRunId,
                               @Param("expectedStatus") String expectedStatus,
                               @Param("newStatus") String newStatus);

    /**
     * FGC-FUN-041 목록 조회
     */
    List<ValidationRunListRow> search(@Param("month") LocalDate month,
                                       @Param("status") String status,
                                       @Param("offset") int offset,
                                       @Param("limit") int limit);

    /**
     * search와 같은 조건(month/status)의 전체 건수 — 페이징 totalElements 계산용
     */
    long count(@Param("month") LocalDate month,
               @Param("status") String status);

    /**
     * 배치 진행 기록 1/4: CREATED → RUNNING 전이와 동시에 current_step을 1로, started_at을 now()로 채움
     *
     * @return 반영된 행 수. 0이면 실행 미존재이거나 이미 CREATED가 아님.
     */
    int transitionToRunning(@Param("validationRunId") Long validationRunId);

    /**
     * 배치 진행 기록 2/4: RUNNING 상태에서 current_step만 전진(②~⑧)
     *
     * @return 반영된 행 수. 0이면 그 사이 상태가 RUNNING이 아니게 됐다는 뜻(동시 실행 등).
     */
    int updateCurrentStep(@Param("validationRunId") Long validationRunId,
                           @Param("currentStep") int currentStep);

    /**
     * 배치 진행 기록 3/4: 8단계(예외생성)까지 전부 성공한 뒤 RUNNING → COMPLETED로 전이
     *
     * @return 반영된 행 수. 0이면 그 사이 상태가 RUNNING이 아니게 됨.
     */
    int transitionToCompleted(@Param("validationRunId") Long validationRunId);

    /**
     * 배치 진행 기록 4/4: 임의의 Step이 실패하면 RUNNING → FAILED로 전이하면서 실패한
     * Step 번호를 current_step에, 실패 사유를 failure_message에 남김
     *
     * @return 반영된 행 수. 0이면 그 사이 상태가 RUNNING이 아니게 됨.
     */
    int transitionToFailed(@Param("validationRunId") Long validationRunId,
                            @Param("currentStep") int currentStep,
                            @Param("failureMessage") String failureMessage);

    /**
     * DailyChangedContractJob의 "하루 1건" 규칙 지원용
     * run_type='MANUAL_CONTRACT'이고 created_at이 [dayStart, dayEnd)
     * 구간(Asia/Seoul 자정~다음날 자정)에 속하는 실행 중
     * 가장 최근(run_no가 가장 큰) 1건을 돌려줌
     */
    ValidationRunRow findManualContractRunCreatedBetween(@Param("dayStart") OffsetDateTime dayStart,
                                                          @Param("dayEnd") OffsetDateTime dayEnd);
}
