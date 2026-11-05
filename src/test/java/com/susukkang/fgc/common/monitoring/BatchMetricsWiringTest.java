package com.susukkang.fgc.common.monitoring;

import com.susukkang.fgc.common.config.BatchMetricsConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.*;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class BatchMetricsWiringTest {
    @Configuration(proxyBeanMethods = false)
    static class Jobs {
        @Bean JobRepository repository() { return new ResourcelessJobRepository(); }
        @Bean Step failingStep(JobRepository repository) {
            return new StepBuilder("failingStep", repository)
                    .tasklet((contribution, context) -> { throw new IllegalStateException("synthetic failure"); },
                            new ResourcelessTransactionManager()).build();
        }
        @Bean Job failingJob(JobRepository repository, Step failingStep) {
            return new JobBuilder("failingJob", repository).start(failingStep).build();
        }
    }

    @Test void failedManagedJobAndStepAreAutomaticallyInstrumented() {
        new ApplicationContextRunner().withUserConfiguration(BatchMetricsConfiguration.class, Jobs.class)
                .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> {
                    var execution = new JobExecution(new JobInstance(1L, "failingJob"), new JobParameters());
                    context.getBean(Job.class).execute(execution);
                    assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
                    var registry = context.getBean(SimpleMeterRegistry.class);
                    assertThat(registry.get("fgc.batch.last.failure.timestamp")
                            .tag("job", "failingJob").gauge().value()).isPositive();
                    assertThat(registry.get("fgc.batch.job").tag("status", "FAILED").timer().count()).isEqualTo(1);
                    assertThat(registry.get("fgc.batch.step").tag("status", "FAILED").timer().count()).isEqualTo(1);
                });
    }
}
