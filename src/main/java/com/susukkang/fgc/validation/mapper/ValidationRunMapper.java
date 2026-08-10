package com.susukkang.fgc.validation.mapper;

import com.susukkang.fgc.validation.dto.ValidationRunRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ValidationRunMapper {

    /** validation_run_id 단건 조회. 없으면 null. */
    ValidationRunRow findById(@Param("validationRunId") Long validationRunId);

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
