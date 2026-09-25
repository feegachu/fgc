package com.susukkang.fgc.common.persistence.support;

import com.susukkang.fgc.base.code.InsurerType;
import com.susukkang.fgc.base.entity.Insurer;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 설명 : 운영 DataSource와 SqlSession을 사용하는 혼합 저장 테스트 전용 구성이다.
 *
 * @author Codex
 * @version 1.0
 * @since 2026-09-24
 */
@TestConfiguration(proxyBeanMethods = false)
public class MixedPersistenceTestSupport {

    @Bean
    MixedInsurerMapper mixedInsurerMapper(SqlSessionTemplate sqlSessionTemplate) {
        // MapperFactoryBean을 별도 Bean으로 선언하면 운영 Mapper 자동 스캔을 막을 수 있다.
        // 기존 SqlSessionTemplate에 테스트 Mapper만 추가하고 트랜잭션 설정은 그대로 사용한다.
        var configuration = sqlSessionTemplate.getSqlSessionFactory().getConfiguration();
        if (!configuration.hasMapper(MixedInsurerMapper.class)) {
            configuration.addMapper(MixedInsurerMapper.class);
        }
        return sqlSessionTemplate.getMapper(MixedInsurerMapper.class);
    }

    public static Insurer newInsurer(String code) {
        Insurer insurer = new Insurer();
        ReflectionTestUtils.setField(insurer, "insurerCode", code);
        ReflectionTestUtils.setField(insurer, "insurerName", "혼합 저장 테스트 " + code);
        ReflectionTestUtils.setField(insurer, "insurerType", InsurerType.LIFE);
        return insurer;
    }

    // @Mapper를 붙이지 않아 이 구성을 import한 테스트에서만 등록된다.
    public interface MixedInsurerMapper {

        @Insert("""
                INSERT INTO fgc.insurer (insurer_code, insurer_name, insurer_type, active_yn)
                VALUES (#{code}, #{name}, 'LIFE', true)
                """)
        int insert(@Param("code") String code, @Param("name") String name);
    }
}
