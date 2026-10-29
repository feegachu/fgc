package com.susukkang.fgc.policy.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration(proxyBeanMethods = false)
public class PolicyClockConfiguration {
    @Bean
    Clock policyClock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
