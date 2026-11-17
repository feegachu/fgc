package com.susukkang.fgc.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.susukkang.fgc.exceptioncase.event.ExceptionCaseClosed;
import com.susukkang.fgc.validation.event.ValidationRunFinalized;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FUN-089: 확정 사건(IF-EVT-07)을 커밋 뒤에 받아 알림을 남기는 연결을 실제 커밋으로 확인한다.
 * 커밋을 봐야 하므로 테스트 트랜잭션을 쓰지 않고, 만든 행은 끝나고 지운다.
 */
@SpringBootTest
class ClosingNotificationListenerIntegrationTest {

    private static final LocalDate MONTH = LocalDate.of(2089, 7, 1);

    @Autowired
    private ApplicationEventPublisher publisher;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoSpyBean
    private ClosingNotificationService notifications;

    private TransactionTemplate transaction;
    private Long runId;

    @BeforeEach
    void createRun() {
        transaction = new TransactionTemplate(transactionManager);
        runId = jdbc.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type)
                VALUES (?, ?, 'PRE_CONFIRM')
                RETURNING validation_run_id
                """, Long.class, MONTH, ThreadLocalRandom.current().nextInt(2_000_000, 3_000_000));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM fgc.closing_notification WHERE validation_run_id = ?", runId);
        jdbc.update("DELETE FROM fgc.validation_run WHERE validation_run_id = ?", runId);
    }

    @Test
    @DisplayName("확정 사건이 커밋되면 알림 기록이 따로 커밋된다")
    void recordsTheNotificationAfterTheFinalizeTransactionCommits() {
        transaction.executeWithoutResult(status -> {
            publisher.publishEvent(finalized());
            assertThat(notificationCount()).isZero();
        });

        // 수신 동의한 설계사가 없으므로 수신 대상 없음 1행이 남는다.
        assertThat(jdbc.queryForObject("""
                SELECT status FROM fgc.closing_notification
                 WHERE validation_run_id = ? AND event_type = 'RUN_FINALIZED'
                """, String.class, runId)).isEqualTo("NO_RECIPIENT");
    }

    @Test
    @DisplayName("확정이 되돌려지면 알림을 남기지 않는다")
    void recordsNothingWhenTheFinalizeTransactionRollsBack() {
        transaction.executeWithoutResult(status -> {
            publisher.publishEvent(finalized());
            status.setRollbackOnly();
        });

        assertThat(notificationCount()).isZero();
    }

    @Test
    @DisplayName("알림 기록이 실패해도 확정 쪽으로 예외가 넘어가지 않는다")
    void doesNotPropagateRecordingFailures() {
        // 없는 실행 번호는 외래키에 걸려 기록이 실패한다.
        ValidationRunFinalized unknownRun = new ValidationRunFinalized(-1L, MONTH, 1L, OffsetDateTime.now());

        assertThatCode(() -> transaction.executeWithoutResult(status -> publisher.publishEvent(unknownRun)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("FGC-FUN-089 예외 종결이 커밋되면 10초 주기를 기다리지 않고 알림 주기를 바로 깨운다")
    void wakesTheCycleAsSoonAsAnExceptionIsClosed() {
        transaction.executeWithoutResult(status -> {
            publisher.publishEvent(new ExceptionCaseClosed(1L));
            verify(notifications, never()).wake();
        });

        verify(notifications).wake();
    }

    @Test
    @DisplayName("FGC-FUN-089 예외 종결이 되돌려지면 깨우지 않는다")
    void doesNotWakeWhenTheClosingTransactionRollsBack() {
        transaction.executeWithoutResult(status -> {
            publisher.publishEvent(new ExceptionCaseClosed(1L));
            status.setRollbackOnly();
        });

        verify(notifications, never()).wake();
    }

    @Test
    @DisplayName("FGC-FUN-089 확정 통지를 기록한 뒤에도 바로 깨워 설계사 문자가 10초를 기다리지 않게 한다")
    void wakesTheCycleAfterRecordingTheFinalizeNotification() {
        transaction.executeWithoutResult(status -> publisher.publishEvent(finalized()));

        InOrder order = inOrder(notifications);
        order.verify(notifications).recordRunFinalized(runId, MONTH);
        order.verify(notifications).wake();
    }

    private ValidationRunFinalized finalized() {
        return new ValidationRunFinalized(runId, MONTH, 1L, OffsetDateTime.now());
    }

    private Integer notificationCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM fgc.closing_notification WHERE validation_run_id = ?", Integer.class, runId);
    }
}
