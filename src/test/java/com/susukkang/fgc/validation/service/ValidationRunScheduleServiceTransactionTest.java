package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.ScheduleHeaderStatus;
import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.contract.repository.ContractLockRepository;
import com.susukkang.fgc.exceptioncase.repository.ExceptionCaseRepository;
import com.susukkang.fgc.policy.service.CommissionPolicyService;
import com.susukkang.fgc.policy.service.FeeRegimeSchedulePolicyService;
import com.susukkang.fgc.schedule.repository.ScheduleHeaderRepository;
import com.susukkang.fgc.schedule.repository.SchedulePreviewRepository;
import com.susukkang.fgc.schedule.repository.ScheduleWriteRepository;
import com.susukkang.fgc.validation.batch.contract.ContractSkip;
import com.susukkang.fgc.validation.batch.contract.ScheduleRegenerationPort;
import com.susukkang.fgc.validation.batch.contract.StepProcessingResult;
import com.susukkang.fgc.validation.batch.contract.ValidationJobContext;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import com.susukkang.fgc.validation.dto.ValidationScheduleState;
import com.susukkang.fgc.validation.repository.ValidationTargetRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SonarCloud S2229 회귀 시험. 배치 Step 밖에서 Spring 프록시로 진입점을 불러도, 정책 누락 경로의
 * MANDATORY 저장(ScheduleWriteRepository)이 진입점 트랜잭션 안에서 성공하고 커밋되는지 본다.
 * 시험 자체에는 트랜잭션을 걸지 않고, DB 대신 EntityManager와 Connection만 가짜로 둔다.
 */
@SpringJUnitConfig(ValidationRunScheduleServiceTransactionTest.Config.class)
class ValidationRunScheduleServiceTransactionTest {

    @MockitoBean
    private ValidationTargetRepository validationTargetRepository;
    @MockitoBean
    private CommissionPolicyService commissionPolicyService;
    @MockitoBean
    private ExceptionCaseRepository exceptionCaseRepository;
    @MockitoBean
    private ScheduleRegenerationBatchItemService itemService;
    @MockitoBean
    private SchedulePreviewRepository previews;
    @MockitoBean
    private FeeRegimeSchedulePolicyService previewPolicies;

    @Autowired
    private ScheduleRegenerationPort port;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private Connection connection;

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void policyReviewSaveSucceedsAndCommitsInsideTheEntryPointTransaction() throws SQLException {
        NativeQuery query = mock(NativeQuery.class, RETURNS_SELF);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.unwrap(NativeQuery.class)).thenReturn(query);
        given(validationTargetRepository.selectScheduleStates(118L)).willReturn(List.of(validState()));
        given(commissionPolicyService.resolveCurrentCommission(10L, PaymentStage.INSURER_TO_GA))
                .willThrow(new FgcBusinessException(FgcErrorCode.COMMON_002, Map.of("reason", "POLICY_MISSING")));

        StepProcessingResult result = port.regenerateSchedules(new ValidationStepContext(118L,
                new ValidationJobContext(LocalDate.of(2026, 7, 1), 1, ValidationRunType.MONTHLY, 1, "s2229")));

        assertThat(result.skips()).singleElement()
                .extracting(ContractSkip::reasonCode).isEqualTo("POLICY_MISSING");
        verify(query).executeUpdate();
        verify(connection).commit();
    }

    private static ValidationScheduleState validState() {
        return ValidationScheduleState.builder()
                .contractId(10L)
                .paymentStage(PaymentStage.GA_TO_FC)
                .scheduleHeaderId(20L)
                .policyVersionId(30L)
                .scheduleVersion(1)
                .scheduleStatus(ScheduleHeaderStatus.PLANNED)
                .activeHeaderCount(1)
                .lineCount(12)
                .distinctLineCount(12)
                .totalAmount(new BigDecimal("1200000"))
                .build();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(ValidationRunScheduleService.class)
    static class Config {

        @Bean
        Connection connection() throws SQLException {
            Connection connection = mock(Connection.class);
            when(connection.getAutoCommit()).thenReturn(true);
            return connection;
        }

        @Bean
        PlatformTransactionManager transactionManager(Connection connection) throws SQLException {
            DataSource dataSource = mock(DataSource.class);
            when(dataSource.getConnection()).thenReturn(connection);
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        EntityManager entityManager() {
            return mock(EntityManager.class);
        }

        // 가짜가 아닌 실제 저장소를 써야 클래스에 붙은 MANDATORY 전파를 프록시가 검사한다.
        @Bean
        ScheduleWriteRepository scheduleWriteRepository(EntityManager entityManager) {
            return new ScheduleWriteRepository(entityManager,
                    mock(ScheduleHeaderRepository.class), mock(ContractLockRepository.class));
        }
    }
}
