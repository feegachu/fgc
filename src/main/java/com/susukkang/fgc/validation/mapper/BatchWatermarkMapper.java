package com.susukkang.fgc.validation.mapper;

import com.susukkang.fgc.validation.dto.BatchWatermarkRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;

/**
 * fgc.batch_watermark 전용 매퍼. "여기까지는 처리했다"는 기준선을 읽고, Step이 성공한 뒤에만 전진시킴
 */
@Mapper
public interface BatchWatermarkMapper {

    /**
     * (jobName, stepName)으로 현재 워터마크 1행을 조회한다. 시드가 이미 넣어 뒀으므로 정상
     * 흐름에선 항상 있다. batch_watermark의 PK가 (job_name, step_name)이라(V2_1 마이그레이션),
     * jobName만으로 조회하면 같은 Job에 Step별 워터마크 행이 여러 개일 때 결과가 1건이라는
     * 보장이 없다 — 그래서 두 값을 모두 받는다.
     */
    BatchWatermarkRow findByJobNameAndStepName(@Param("jobName") String jobName, @Param("stepName") String stepName);

    /**
     * Step이 성공했을 때만 부른다. lastProcessedAt/lastRunId/lastSuccessAt을 새 값으로 덮어쓰고,
     * processedCount는 이번에 처리한 건수만큼 더함(누적)
     *
     * @return 반영된 행 수. 정상 흐름에서는 항상 1(시드 행이 있으므로) — 0이면 (jobName, stepName)
     *         오타 등 배선 문제를 의심해야 한다.
     */
    int advance(@Param("jobName") String jobName,
                @Param("stepName") String stepName,
                @Param("lastProcessedAt") OffsetDateTime lastProcessedAt,
                @Param("lastRunId") Long lastRunId,
                @Param("processedCountDelta") long processedCountDelta);
}
