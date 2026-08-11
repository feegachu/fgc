package com.susukkang.fgc.validation.mapper;

import com.susukkang.fgc.validation.dto.ValidationRunInsertRow;
import com.susukkang.fgc.validation.dto.ValidationRunListRow;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDate;
import java.util.List;

@Mapper
public interface ValidationRunMapper {

    /** validation_run_id 단건 조회. 없으면 null. */
    ValidationRunRow findById(@Param("validationRunId") Long validationRunId);

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
     * FGC-FUN-041 목록 조회(#41). month/status 는 둘 다 선택조건 — null이면 그 조건을 걸지 않는다.
     * ValidationRunMapper.xml에 이 id로 &lt;select&gt;를 작성해야 한다. CapCheckMapper.xml의
     * searchWhere/search를 그대로 참고하면 된다:
     *   - FROM fgc.validation_run vr
     *   - LEFT JOIN fgc.app_user triggered ON triggered.user_id = vr.triggered_by
     *   - LEFT JOIN fgc.app_user finalized ON finalized.user_id = vr.finalized_by
     *     (DashboardMapper.xml의 findRecentValidationRuns와 동일한 조인 — login_id를
     *     triggeredBy/finalizedBy로 별칭)
     *   - month가 있으면 WHERE vr.validation_month = #{month} (date_trunc 불필요 —
     *     validation_month 자체가 그 달 1일로 저장되는 컬럼이라 CapCheck의 as_of_date 유도와 다름)
     *   - status가 있으면 AND vr.status = #{status}
     *   - ORDER BY vr.validation_month DESC, vr.run_no DESC (최근 검증월·회차 우선 —
     *     RecentValidationRunRow가 vr.created_at DESC로 정렬하는 것과는 다른 정렬 기준이니 주의)
     *   - LIMIT #{limit} OFFSET #{offset}
     * resultType은 ValidationRunListRow — 컬럼 별칭을 그 필드명과 정확히 맞춰야 한다
     * (map-underscore-to-camel-case 미설정 상태).
     */
    List<ValidationRunListRow> search(@Param("month") LocalDate month,
                                       @Param("status") String status,
                                       @Param("offset") int offset,
                                       @Param("limit") int limit);

    /**
     * search와 같은 조건(month/status)의 전체 건수 — 페이징 totalElements 계산용.
     * ValidationRunMapper.xml에 search와 WHERE 절을 공유하는 &lt;sql id="searchWhere"&gt;를 두고
     * 두 &lt;select&gt;가 &lt;include&gt;로 재사용하면 두 쿼리의 조건이 어긋날 일이 없다
     * (CapCheckMapper.xml의 searchWhere 참고).
     */
    long count(@Param("month") LocalDate month,
               @Param("status") String status);
}
