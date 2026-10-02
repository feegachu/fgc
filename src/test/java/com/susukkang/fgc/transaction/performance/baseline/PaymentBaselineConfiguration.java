package com.susukkang.fgc.transaction.performance.baseline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.audit.service.AuditLogService;
import com.susukkang.fgc.base.repository.AgentRepository;
import com.susukkang.fgc.cap.service.CapCalculator;
import com.susukkang.fgc.cap.service.CapExceptionService;
import com.susukkang.fgc.cap.service.CapValidator;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.policy.service.CommissionPolicyService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;
import java.io.IOException;

/**
 * 설명 : d1a603df 지급 서비스·Mapper 기준 구현의 명시적 테스트 등록.
 * 운영 Mapper 검색 경로와 운영 서비스의 기본 주입 후보에는 참여하지 않는다.
 *
 * @author hjKang
 * @since 2026-09-30
 * @version 1.0
 */
@TestConfiguration(proxyBeanMethods = false)
public class PaymentBaselineConfiguration {

    @Bean(defaultCandidate = false)
    BaselineMappers paymentBaselineMappers(DataSource dataSource) throws IOException {
        Configuration configuration = new Configuration(new Environment("payment-baseline-d1a603df",
                new SpringManagedTransactionFactory(), dataSource));
        // d1a603df application.yml과 동일하다. 조회마다 같은 트랜잭션의 DB 값을 다시 읽는다.
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        // 비교 양쪽의 2차 캐시를 명시적으로 끈다. 기준 XML에도 cache 선언은 없다.
        configuration.setCacheEnabled(false);
        for (String resource : new String[]{
                "payment-baseline/BaselineCommissionPaymentMapper.xml",
                "payment-baseline/BaselineCapCheckMapper.xml",
                "payment-baseline/BaselineCapExceptionMapper.xml"
        }) {
            try (var input = new ClassPathResource(resource).getInputStream()) {
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        // 별도 SqlSessionFactory/SqlSessionTemplate Bean을 노출하지 않아 운영 자동설정을 보존한다.
        SqlSessionTemplate session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        return new BaselineMappers(
                session.getMapper(BaselineCommissionPaymentMapper.class),
                session.getMapper(BaselineCapCheckMapper.class),
                session.getMapper(BaselineCapExceptionMapper.class));
    }

    @Bean(name = "baselineCapExceptionService", defaultCandidate = false)
    BaselineCapExceptionService baselineCapExceptionService(
            @Qualifier("paymentBaselineMappers") BaselineMappers mappers
    ) {
        return new BaselineCapExceptionService(mappers.exceptions());
    }

    @Bean(name = "baselineCommissionPaymentService", defaultCandidate = false)
    BaselineCommissionPaymentService baselineCommissionPaymentService(
            @Qualifier("paymentBaselineMappers") BaselineMappers mappers,
            AgentRepository agentRepository,
            ObjectMapper objectMapper,
            CapValidator capValidator,
            CapCalculator capCalculator,
            @Qualifier("baselineCapExceptionService") CapExceptionService capExceptionService,
            FgcMessageResolver messageResolver,
            AuditLogService auditLogService,
            CommissionPolicyService commissionPolicyService
    ) {
        return new BaselineCommissionPaymentService(
                mappers.payments(), mappers.capChecks(), agentRepository, objectMapper,
                capValidator, capCalculator, capExceptionService, messageResolver,
                auditLogService, commissionPolicyService);
    }

    record BaselineMappers(
            BaselineCommissionPaymentMapper payments,
            BaselineCapCheckMapper capChecks,
            BaselineCapExceptionMapper exceptions
    ) { }
}
