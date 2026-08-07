package com.susukkang.fgc.validation.mapper;

import com.susukkang.fgc.validation.dto.ValidationRunInsertRow;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

@Mapper
public interface ValidationRunMapper {

    /** validation_run_id 단건 조회. 없으면 null. */
    ValidationRunRow findById(@Param("validationRunId") Long validationRunId);

    /**
     * validationMonth에 이미 있는 실행들 중 최대 run_no + 1. 하나도 없으면 1.
     * uq_validation_run(validation_month, run_no) 유니크 제약이 최종 방어선이라, 두 요청이
     * 동시에 이 메서드를 불러 같은 값을 받아가도 INSERT 하나는 제약 위반으로 실패한다 —
     * 즉 이 메서드 자체는 "힌트"일 뿐 동시성 보장 수단이 아니다.
     */
    Integer findNextRunNo(@Param("validationMonth") LocalDate validationMonth);

    /**
     * validationMonth에 run_type='MONTHLY'이고 status가 CREATED/RUNNING인(=활성) 실행이
     * 있는지. 이슈의 "동일 월 활성 MONTHLY 실행 한 건만 허용"에 대한 사전 체크(친절한 409
     * 메시지용)일 뿐, 진짜 방어선은 V1의 부분 유니크 인덱스
     * uq_validation_run_active_month다(validation_month, WHERE run_type='MONTHLY' AND
     * status IN ('CREATED','RUNNING')) — 두 요청이 동시에 이 메서드에서 둘 다 false를 받아도
     * 나중 INSERT가 그 인덱스 위반으로 실패한다.
     */
    boolean existsActiveMonthlyRun(@Param("validationMonth") LocalDate validationMonth);

    /**
     * validation_run 1행 INSERT. status/current_step은 DB 기본값에 맡긴다(row 자체에
     * 그 필드가 없다 — ValidationRunInsertRow 주석 참고). useGeneratedKeys로
     * row.validationRunId가 채워진다.
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
}
