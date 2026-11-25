package com.susukkang.fgc.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.susukkang.fgc.notification.sms.SmsGateway;
import java.time.LocalDate;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * FUN-089 발송 경계: 잡은 기록을 먼저 커밋하고, 문자 사업자 호출은 DB 트랜잭션 밖에서 한다.
 * 커밋을 봐야 하므로 테스트 트랜잭션을 쓰지 않고, 만든 행은 끝나고 지운다.
 */
@SpringBootTest
class ClosingNotificationDispatchBoundaryTest {

    private static final String PHONE = "01000000102";
    private static final String TEXT = "[FGC] 발송 경계 확인";

    @Autowired
    private ClosingNotificationService service;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private SmsGateway smsGateway;

    private Long runId;
    private Long userId;
    private Long notificationId;

    @BeforeEach
    void commitOnePendingNotification() {
        runId = jdbc.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type)
                VALUES (?, ?, 'PRE_CONFIRM')
                RETURNING validation_run_id
                """, Long.class, LocalDate.of(2089, 8, 1), ThreadLocalRandom.current().nextInt(3_000_000, 4_000_000));
        userId = jdbc.queryForObject("SELECT user_id FROM fgc.app_user WHERE login_id = 'agent01'", Long.class);
        jdbc.update("INSERT INTO fgc.user_notification_setting (user_id, mobile_phone) VALUES (?, ?)", userId, PHONE);
        notificationId = jdbc.queryForObject("""
                INSERT INTO fgc.closing_notification (event_type, validation_run_id, recipient_user_id, message_text)
                VALUES ('RUN_FINALIZED', ?, ?, ?)
                RETURNING closing_notification_id
                """, Long.class, runId, userId, TEXT);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM fgc.closing_notification WHERE validation_run_id = ?", runId);
        jdbc.update("DELETE FROM fgc.user_notification_setting WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM fgc.validation_run WHERE validation_run_id = ?", runId);
    }

    @Test
    @DisplayName("잡은 기록을 먼저 커밋한 뒤 트랜잭션 밖에서 문자 사업자를 부른다")
    void commitsTheClaimBeforeCallingTheProviderOutsideATransaction() {
        when(smsGateway.send(anyString(), anyString())).thenAnswer(call -> {
            // 사업자가 느려도 DB 연결과 행 잠금을 붙잡지 않아야 한다.
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            // 발송 중에 멈춰도 같은 알림을 곧바로 다시 잡지 않도록, 시도 횟수는 부르기 전에 커밋돼 있어야 한다.
            assertThat(column("attempt_count", Integer.class)).isEqualTo(1);
            return "M4V-BOUNDARY";
        });

        assertThat(service.dispatchPending()).isEqualTo(1);

        verify(smsGateway).send(PHONE, TEXT);
        assertThat(column("status", String.class)).isEqualTo("SENT");
    }

    private <T> T column(String name, Class<T> type) {
        return jdbc.queryForObject(
                "SELECT " + name + " FROM fgc.closing_notification WHERE closing_notification_id = ?", type, notificationId);
    }
}
