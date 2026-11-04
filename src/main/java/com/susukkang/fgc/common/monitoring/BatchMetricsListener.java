package com.susukkang.fgc.common.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.*;

/** Bounded labels only: no execution IDs, parameters, user data or exception messages. */
public final class BatchMetricsListener implements JobExecutionListener, StepExecutionListener {
    private static final Logger LOG = LoggerFactory.getLogger(BatchMetricsListener.class);
    private final Supplier<MeterRegistry> registry;
    private final AtomicLong lastFailure = new AtomicLong();
    public BatchMetricsListener(Supplier<MeterRegistry> registry) { this.registry = registry; }

    @Override
    public void beforeJob(JobExecution job) {
        registry.get().gauge("fgc.batch.last.failure.timestamp", Tags.of("job", job.getJobInstance().getJobName()), lastFailure);
    }

    @Override
    public void afterJob(JobExecution job) {
        String name = job.getJobInstance().getJobName();
        Tags tags = Tags.of("job", name, "status", job.getStatus().name());
        record("fgc.batch.job", tags, job.getStartTime(), job.getEndTime());
        if (job.getStatus() == BatchStatus.FAILED) lastFailure.set(Instant.now().getEpochSecond());
        LOG.atInfo().addKeyValue("event", "batch_job_finished").addKeyValue("job", name)
                .addKeyValue("status", job.getStatus().name()).log("Batch job finished");
    }

    @Override
    public ExitStatus afterStep(StepExecution step) {
        // Partition suffixes may contain run-specific IDs: retain only the configured step name.
        String name = step.getStepName().split(":", 2)[0];
        Tags tags = Tags.of("job", step.getJobExecution().getJobInstance().getJobName(),
                "step", name, "status", step.getStatus().name());
        record("fgc.batch.step", tags, step.getStartTime(), step.getEndTime());
        registry.get().counter("fgc.batch.items", tags.and("operation", "read")).increment(step.getReadCount());
        registry.get().counter("fgc.batch.items", tags.and("operation", "written")).increment(step.getWriteCount());
        registry.get().counter("fgc.batch.items", tags.and("operation", "skipped")).increment(step.getSkipCount());
        LOG.atInfo().addKeyValue("event", "batch_step_finished")
                .addKeyValue("job", step.getJobExecution().getJobInstance().getJobName()).addKeyValue("step", name)
                .addKeyValue("status", step.getStatus().name()).addKeyValue("read", step.getReadCount())
                .addKeyValue("written", step.getWriteCount()).addKeyValue("skipped", step.getSkipCount())
                .log("Batch step finished");
        return null; // Preserve existing exit status.
    }

    private void record(String name, Tags tags, LocalDateTime start, LocalDateTime end) {
        if (start != null && end != null) {
            Timer.builder(name).tags(tags).register(registry.get()).record(Duration.between(start, end).abs());
        }
    }
}
