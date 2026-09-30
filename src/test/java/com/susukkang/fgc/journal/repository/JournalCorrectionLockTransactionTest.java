package com.susukkang.fgc.journal.repository;

import com.susukkang.fgc.exceptioncase.repository.ExceptionActionRepository;
import com.susukkang.fgc.exceptioncase.repository.ExceptionCaseRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 설명 : 실제 Spring 프록시로 정정 잠금의 호출자 트랜잭션 계약을 검증한다.
 * DB 잠금 자체는 JournalCorrectionConcurrencyIntegrationTest에서 검증한다.
 *
 * @author Codex
 * @version 1.0
 * @since 2026-09-29
 */
@SpringJUnitConfig(JournalCorrectionLockTransactionTest.Config.class)
class JournalCorrectionLockTransactionTest {

    @Autowired
    private JournalCorrectionRepository journalRepository;
    @Autowired
    private JournalCorrectionExceptionRepository correctionExceptionRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private ExceptionCaseRepository caseRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void setUp() {
        reset(entityManager, caseRepository);
        NativeQuery query = mock(NativeQuery.class, RETURNS_SELF);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.unwrap(NativeQuery.class)).thenReturn(query);
        when(query.getSingleResult()).thenReturn(1);
    }

    @Test
    void originalRowLockRejectsCallsWithoutAnOuterTransactionBeforeExecutingSql() {
        assertThatThrownBy(() -> journalRepository.findHeaderForUpdate(-1L))
                .isInstanceOf(IllegalTransactionStateException.class);
        verifyNoInteractions(entityManager);
    }

    @Test
    void numberingLockRejectsCallsWithoutAnOuterTransactionBeforeExecutingSql() {
        assertThatThrownBy(() -> journalRepository.lockJournalNumbering("journal-no:2026-09"))
                .isInstanceOf(IllegalTransactionStateException.class);
        verifyNoInteractions(entityManager);
    }

    @Test
    void exceptionLockFacadeDoesNotCreateAShortLivedTransaction() {
        assertThatThrownBy(() -> correctionExceptionRepository.findJournalCorrectionTargetForUpdate(-1L))
                .isInstanceOf(IllegalTransactionStateException.class);
        verifyNoInteractions(caseRepository);
    }

    @Test
    void lockMethodsLeaveCompletionToTheCallingTransaction() {
        AtomicBoolean completed = new AtomicBoolean();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int completionStatus) {
                    completed.set(true);
                }
            });

            assertThat(journalRepository.findHeaderForUpdate(-1L)).isNull();
            journalRepository.lockJournalNumbering("journal-no:2026-09");
            assertThat(correctionExceptionRepository.findJournalCorrectionTargetForUpdate(-1L)).isNull();

            verify(caseRepository).findJournalCorrectionTargetForUpdate(-1L);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(completed).isFalse();
        });

        assertThat(completed).isTrue();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Config {
        @Bean
        PlatformTransactionManager transactionManager() throws SQLException {
            // Spring의 실제 트랜잭션 전파를 사용하고 JDBC 연결만 대체한다.
            Connection connection = mock(Connection.class);
            when(connection.getAutoCommit()).thenReturn(true);
            DataSource dataSource = mock(DataSource.class);
            when(dataSource.getConnection()).thenReturn(connection);
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        EntityManager entityManager() {
            return mock(EntityManager.class);
        }

        @Bean
        ExceptionCaseRepository caseRepository() {
            return mock(ExceptionCaseRepository.class);
        }

        @Bean
        JournalCorrectionRepository journalRepository(EntityManager entityManager) {
            return new JournalCorrectionRepository(entityManager);
        }

        @Bean
        JournalCorrectionExceptionRepository correctionExceptionRepository(
                EntityManager entityManager, ExceptionCaseRepository caseRepository) {
            return new JournalCorrectionExceptionRepository(
                    entityManager, caseRepository, mock(ExceptionActionRepository.class));
        }
    }
}
