package com.susukkang.fgc.transaction.performance.baseline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.audit.service.AuditLogService;
import com.susukkang.fgc.base.repository.AgentRepository;
import com.susukkang.fgc.cap.service.CapCalculator;
import com.susukkang.fgc.cap.service.CapExceptionService;
import com.susukkang.fgc.cap.service.CapValidator;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.policy.service.CommissionPolicyService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

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
    BaselineMappers paymentBaselineMappers(DataSource dataSource) {
        return new BaselineMappers(new CommissionPaymentJdbcBaseline(dataSource),
                new PaymentCapCheckJdbcBaseline(dataSource), new PaymentCapExceptionJdbcBaseline(dataSource));
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
            CommissionPaymentJdbcBaseline payments,
            PaymentCapCheckJdbcBaseline capChecks,
            PaymentCapExceptionJdbcBaseline exceptions
    ) { }
}
