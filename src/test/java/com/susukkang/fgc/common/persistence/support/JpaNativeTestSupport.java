package com.susukkang.fgc.common.persistence.support;

import com.susukkang.fgc.base.code.InsurerType;
import com.susukkang.fgc.base.entity.Insurer;
import jakarta.persistence.EntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;

/** JPA 엔티티 저장·네이티브 SQL·Batch의 동일 트랜잭션 참여를 검증하는 구성. */
@TestConfiguration(proxyBeanMethods = false)
public class JpaNativeTestSupport {
    @Bean NativeInsurerWriter nativeInsurerWriter(EntityManager entityManager) {
        return new NativeInsurerWriter(entityManager);
    }

    public static Insurer newInsurer(String code) {
        Insurer insurer = new Insurer();
        ReflectionTestUtils.setField(insurer, "insurerCode", code);
        ReflectionTestUtils.setField(insurer, "insurerName", "트랜잭션 테스트 " + code);
        ReflectionTestUtils.setField(insurer, "insurerType", InsurerType.LIFE);
        return insurer;
    }

    @Repository
    @Transactional(propagation = Propagation.MANDATORY)
    public static class NativeInsurerWriter {
        private final EntityManager entityManager;
        public NativeInsurerWriter(EntityManager entityManager) { this.entityManager = entityManager; }
        public int insert(String code, String name) {
            entityManager.flush();
            return entityManager.createNativeQuery("""
                    INSERT INTO fgc.insurer (insurer_code, insurer_name, insurer_type, active_yn)
                    VALUES (:code, :name, 'LIFE', true)
                    """).setParameter("code", code).setParameter("name", name).executeUpdate();
        }
    }
}
