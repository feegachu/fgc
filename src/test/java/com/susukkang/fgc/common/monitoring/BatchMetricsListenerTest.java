package com.susukkang.fgc.common.monitoring;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.*;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

class BatchMetricsListenerTest {
    @Test void failedJobRemainsVisibleAfterSuccessfulRetry() {
        var registry = new SimpleMeterRegistry();
        var listener = new BatchMetricsListener(() -> registry);
        var job = new JobExecution(new JobInstance(1L, "monthly"), new JobParameters());
        job.setStartTime(LocalDateTime.now().minusSeconds(2)); job.setEndTime(LocalDateTime.now());
        listener.beforeJob(job); job.setStatus(BatchStatus.FAILED); listener.afterJob(job);
        double failure = registry.get("fgc.batch.last.failure.timestamp").gauge().value();
        assertThat(failure).isPositive();
        job.setStatus(BatchStatus.COMPLETED); listener.afterJob(job);
        assertThat(registry.get("fgc.batch.last.failure.timestamp").gauge().value()).isEqualTo(failure);
        assertThat(registry.get("fgc.batch.job").tag("status", "FAILED").timer().count()).isEqualTo(1);
    }
    @Test void stepRecordsCountsAndFailurePointWithoutChangingExitStatus() {
        var registry = new SimpleMeterRegistry(); var listener = new BatchMetricsListener(() -> registry);
        var job = new JobExecution(new JobInstance(1L, "monthly"), new JobParameters());
        var step = new StepExecution("calculate:partition123", job);
        step.setStartTime(LocalDateTime.now().minusSeconds(3)); step.setEndTime(LocalDateTime.now());
        step.setStatus(BatchStatus.FAILED); step.setReadCount(12); step.setWriteCount(10); step.setProcessSkipCount(2);
        assertThat(listener.afterStep(step)).isNull();
        assertThat(registry.get("fgc.batch.items").tag("operation", "read").counter().count()).isEqualTo(12);
        assertThat(registry.get("fgc.batch.items").tag("operation", "skipped").counter().count()).isEqualTo(2);
        assertThat(registry.get("fgc.batch.step").tag("step", "calculate").tag("status", "FAILED").timer().count()).isEqualTo(1);
    }
}
