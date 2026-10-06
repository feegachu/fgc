package com.susukkang.fgc.validation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * 설명 : fgc.batch_watermark 1행 — 배치 Job(Step 단위)이 어디까지 처리했는지 나타내는
 * 기준선. Step이 성공했을 때만 {@code BatchWatermarkRepository.advance}로 전진시킨다.
 * PK가 (job_name, step_name)이라(V2_1 마이그레이션) {@code @IdClass}를 쓴다.
 */
@Entity
@IdClass(BatchWatermark.Key.class)
@Table(name = "batch_watermark", schema = "fgc")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BatchWatermark {

    @Id
    @Column(name = "job_name", length = 100)
    private String jobName;

    @Id
    @Column(name = "step_name", length = 100)
    private String stepName;

    @Column(name = "last_processed_at", nullable = false)
    private OffsetDateTime lastProcessedAt;

    @Column(name = "last_run_id")
    private Long lastRunId;

    @Column(name = "last_success_at")
    private OffsetDateTime lastSuccessAt;

    @Column(name = "processed_count", nullable = false)
    private Long processedCount;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "updated_by", nullable = false, length = 60)
    private String updatedBy;

    // trg_batch_watermark_touch(V2)가 UPDATE 때마다 자동으로 채운다 — 애플리케이션은 쓰지 않는다.
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    @Getter
    @EqualsAndHashCode
    @NoArgsConstructor
    public static class Key implements Serializable {
        private String jobName;
        private String stepName;

        public Key(String jobName, String stepName) {
            this.jobName = jobName;
            this.stepName = stepName;
        }
    }
}
