package com.susukkang.fgc.validation.mapper;

import com.susukkang.fgc.validation.dto.AgentCapMonitoringRow;
import com.susukkang.fgc.validation.dto.ValidationRunListRow;
import com.susukkang.fgc.validation.dto.ValidationRunResultSummaryRow;
import com.susukkang.fgc.validation.dto.ValidationTargetListRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * IF-API-47 상세 화면 전용 읽기 매퍼.
 * 배치·생성이 쓰는 ValidationRunMapper와 분리해 둔다 — 확정(FUN-044) 브랜치가
 * ValidationRunMapper.xml을 수정 중이라 충돌 면적을 줄이는 목적도 있다.
 */
@Mapper
public interface ValidationRunDetailMapper {

    /** 실행 헤더 1건 — search와 같은 app_user 조인(login_id)을 id 조건으로. 없으면 null. */
    ValidationRunListRow findHeaderById(@Param("validationRunId") Long validationRunId);

    /** ③ 대상 선별 결과 — 검토필요·제외 먼저, 이후 id 순. */
    List<ValidationTargetListRow> findTargets(@Param("validationRunId") Long validationRunId,
                                              @Param("limit") int limit);

    /** ③건수 + ④결과 요약 4블록을 한 번에 집계 */
    ValidationRunResultSummaryRow summarize(@Param("validationRunId") Long validationRunId);

    /**
     * FGC-FUN-043 "설계사·조직 합계는 모니터링 지표로만 사용" — cap_check를 설계사별로
     * 묶어 참고용 건수만 돌려준다. 계약별 result_status는 이 집계와 무관하게 그대로
     * 유지된다(변경·덮어쓰기 없음).
     */
    List<AgentCapMonitoringRow> summarizeCapByAgent(@Param("validationRunId") Long validationRunId);
}
