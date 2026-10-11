package com.susukkang.fgc.common.config;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 현재 트랜잭션의 공유 EntityManager로 타입 안전한 동적 검색을 생성한다. */
@Configuration(proxyBeanMethods = false)
public class QuerydslConfig {
    @Bean
    JPAQueryFactory jpaQueryFactory(EntityManager entityManager) {
        return new JPAQueryFactory(entityManager);
    }
}
