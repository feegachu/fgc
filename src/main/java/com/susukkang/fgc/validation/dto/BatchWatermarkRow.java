package com.susukkang.fgc.validation.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * fgc.batch_watermark 1행. "이 Job이 어디까지 처리했는지"를 나타내는 기준
 */
@Getter
@Setter
public class BatchWatermarkRow {
    private String jobName;
    private String stepName;
    private OffsetDateTime lastProcessedAt;
    private Long lastRunId;
    private OffsetDateTime lastSuccessAt;
    private Long processedCount;
}
