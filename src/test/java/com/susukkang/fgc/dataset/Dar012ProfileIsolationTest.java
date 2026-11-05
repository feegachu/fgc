package com.susukkang.fgc.dataset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** 시드명세 §50: 생성기는 기본·test 프로필에서 자동 실행되거나 등록되지 않는다. */
@SpringBootTest
class Dar012ProfileIsolationTest {
    @Autowired
    private ApplicationContext context;

    @Test
    void generatorIsNotRegisteredWithoutTheDar012Profile() {
        assertThat(context.getEnvironment().getActiveProfiles()).doesNotContain("dar012");
        assertThat(context.getBeansOfType(Dar012Runner.class)).isEmpty();
    }
}
