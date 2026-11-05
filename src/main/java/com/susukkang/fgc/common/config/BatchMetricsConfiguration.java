package com.susukkang.fgc.common.config;

import com.susukkang.fgc.common.monitoring.BatchMetricsListener;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.batch.core.job.AbstractJob;
import org.springframework.batch.core.step.AbstractStep;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Attach to all managed jobs/steps, including partition workers, without changing business listeners. */
@Configuration(proxyBeanMethods = false)
public class BatchMetricsConfiguration {
    private BatchMetricsConfiguration() {}

    @Bean
    static BeanPostProcessor batchMetricsRegistration(ObjectProvider<MeterRegistry> registry) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof AbstractJob job) {
                    job.registerJobExecutionListener(new BatchMetricsListener(registry::getObject));
                }
                if (bean instanceof AbstractStep step) {
                    step.registerStepExecutionListener(new BatchMetricsListener(registry::getObject));
                }
                return bean;
            }
        };
    }
}
