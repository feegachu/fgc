package com.susukkang.fgc.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.notification.dto.ClosingNotificationResponse;
import com.susukkang.fgc.notification.repository.ClosingNotificationRepository;
import com.susukkang.fgc.notification.sms.SmsGateway;
import com.susukkang.fgc.notification.sms.SmsSendException;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/** FUN-089 마감 알림: 확정 조건 충족 감지, 수신자 선정, 발송·재시도·재발송을 실제 PostgreSQL 로 확인한다. 사업자는 부르지 않는다. */
@SpringBootTest
@Transactional
class ClosingNotificationServiceIntegrationTest {

    private static final String ADMIN_PHONE = "01000000101";
    private static final String GA_ADMIN_PHONE = "01000000102";

    @Autowired
    private ClosingNotificationService service;
    @Autowired
    private ClosingNotificationRepository notificationRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private SmsGateway smsGateway;

    @Test
    @DisplayName("확정 조건이 모두 충족되면 수신 동의한 확정 권한자마다 한 번만 알림을 만든다")
    void recordsFinalizeReadyOncePerRunForFinalizersWhoOptedIn() {
        Long runId = createCompletedRun(LocalDate.of(2088, 1, 1));
        optIn("admin", ADMIN_PHONE);
        optIn("gaadmin", GA_ADMIN_PHONE);
        optIn("settle01", "01000000103");

        service.detectFinalizeReady();
        service.detectFinalizeReady();

        List<Map<String, Object>> rows = notifications(runId);
        assertThat(rows).extracting(row -> row.get("login_id")).containsExactlyInAnyOrder("admin", "gaadmin");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row).containsEntry("event_type", "FINALIZE_READY").containsEntry("status", "PENDING");
            assertThat((String) row.get("message_text")).contains("2088년 1월").contains("확정");
            // 한글은 2바이트로 세어 단문(90바이트) 안에 들어야 장문 요금으로 넘어가지 않는다.
            assertThat(((String) row.get("message_text")).getBytes(Charset.forName("EUC-KR")).length).isLessThanOrEqualTo(90);
        });
    }

    @Test
    @DisplayName("미처리 예외가 남아 있는 동안은 알리지 않고, 다 풀린 뒤에 알린다")
    void waitsUntilTheChecklistHasNoOpenItems() {
        LocalDate month = LocalDate.of(2088, 2, 1);
        Long runId = createCompletedRun(month);
        optIn("gaadmin", GA_ADMIN_PHONE);
        Long exceptionId = jdbc.queryForObject("""
                INSERT INTO fgc.exception_case(exception_key,exception_type,severity,status,validation_run_id,
                    validation_month,source_entity_type,source_entity_id,title)
                VALUES (?,'RECONCILIATION_MISMATCH','HIGH','NEW',?,?,'VALIDATION_RUN',?,'미처리 대사 불일치')
                RETURNING exception_case_id
                """, Long.class, "FUN089-" + runId, runId, month, runId.toString());

        service.detectFinalizeReady();
        assertThat(notifications(runId)).isEmpty();

        jdbc.update("UPDATE fgc.exception_case SET status='IN_REVIEW' WHERE exception_case_id=?", exceptionId);
        jdbc.update("UPDATE fgc.exception_case SET status='RESOLVED' WHERE exception_case_id=?", exceptionId);
        service.detectFinalizeReady();
        assertThat(notifications(runId)).hasSize(1);
    }

    @Test
    @DisplayName("같은 달에 뒤에 만든 실행이 있으면 앞 실행은 확정 조건을 다시 보지 않는다")
    void skipsRunsSupersededByALaterRunOfTheSameMonth() {
        LocalDate month = LocalDate.of(2088, 6, 1);
        Long older = createCompletedRun(month);
        Long newer = createCompletedRun(month);

        // 확정 조건 7(#491)과 같은 기준이다. 앞 실행은 어차피 확정할 수 없으므로 10초마다 체크리스트를 다시 계산하지 않는다.
        assertThat(notificationRepository.findFinalizeReadyCandidates())
                .extracting(ClosingNotificationRepository.RunMonth::validationRunId)
                .contains(newer).doesNotContain(older);
    }

    @Test
    @DisplayName("수신 동의한 확정 권한자가 없으면 수신 대상 없음 한 줄만 남긴다")
    void recordsNoRecipientWhenNoFinalizerOptedIn() {
        Long runId = createCompletedRun(LocalDate.of(2088, 3, 1));
        optIn("settle01", "01000000103");

        service.detectFinalizeReady();
        service.detectFinalizeReady();

        assertThat(notifications(runId)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("status", "NO_RECIPIENT").containsEntry("login_id", null));
    }

    @Test
    @DisplayName("확정되면 그 달에 확정 지급이 있는 수신 동의 설계사에게만 알림을 만든다")
    void recordsRunFinalizedForAgentsPaidThatMonth() {
        Map<String, Object> paid = jdbc.queryForMap("""
                SELECT u.login_id, ct.settlement_month
                  FROM fgc.commission_transaction ct
                  JOIN fgc.app_user u ON u.agent_id = ct.recipient_agent_id
                  JOIN fgc.app_role r ON r.role_id = u.role_id AND r.role_code = 'AGENT'
                 WHERE ct.payment_stage = 'GA_TO_FC' AND ct.status = 'CONFIRMED' AND ct.cashflow_type = 'PAYMENT'
                 ORDER BY ct.settlement_month DESC, u.user_id LIMIT 1
                """);
        String agentLogin = (String) paid.get("login_id");
        LocalDate paidMonth = ((java.sql.Date) paid.get("settlement_month")).toLocalDate();
        optIn(agentLogin, "01000000201");
        optIn("gaadmin", GA_ADMIN_PHONE);
        Long paidRun = createRun(paidMonth);
        Long emptyRun = createRun(LocalDate.of(2088, 4, 1));

        service.recordRunFinalized(paidRun, paidMonth);
        service.recordRunFinalized(paidRun, paidMonth);
        service.recordRunFinalized(emptyRun, LocalDate.of(2088, 4, 1));

        assertThat(notifications(paidRun)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("event_type", "RUN_FINALIZED").containsEntry("status", "PENDING")
                    .containsEntry("login_id", agentLogin);
            assertThat((String) row.get("message_text"))
                    .contains(paidMonth.getYear() + "년 " + paidMonth.getMonthValue() + "월").contains("명세");
        });
        assertThat(notifications(emptyRun)).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("status", "NO_RECIPIENT"));
    }

    @Test
    @DisplayName("대기 중인 알림을 등록된 번호로 보내고 발송 완료로 남긴다")
    void sendsPendingNotificationsToTheRegisteredNumber() {
        Long runId = readyRunFor("gaadmin", GA_ADMIN_PHONE);
        when(smsGateway.send(anyString(), anyString())).thenReturn("M4V-TEST-1");

        assertThat(service.dispatchPending()).isEqualTo(1);

        Map<String, Object> row = notifications(runId).get(0);
        assertThat(row).containsEntry("status", "SENT").containsEntry("provider_message_id", "M4V-TEST-1")
                .containsEntry("attempt_count", 1).containsEntry("error_code", null);
        assertThat(row.get("sent_at")).isNotNull();
        verify(smsGateway).send(GA_ADMIN_PHONE, (String) row.get("message_text"));
        assertThat(service.dispatchPending()).isZero();
    }

    @Test
    @DisplayName("다시 보낼 수 있는 실패는 간격을 두고 세 번까지 시도한 뒤 실패로 끝낸다")
    void retriesRetryableFailuresThreeTimes() {
        Long runId = readyRunFor("gaadmin", GA_ADMIN_PHONE);
        when(smsGateway.send(anyString(), anyString())).thenThrow(new SmsSendException("TIMEOUT", true));

        service.dispatchPending();
        assertThat(notifications(runId).get(0)).containsEntry("status", "PENDING").containsEntry("attempt_count", 1)
                .containsEntry("error_code", "TIMEOUT");
        // 다음 시도 시각 전에는 다시 잡지 않는다.
        assertThat(service.dispatchPending()).isZero();

        makeDue(runId);
        service.dispatchPending();
        makeDue(runId);
        service.dispatchPending();

        assertThat(notifications(runId).get(0)).containsEntry("status", "FAILED").containsEntry("attempt_count", 3)
                .containsEntry("error_code", "TIMEOUT");
        makeDue(runId);
        assertThat(service.dispatchPending()).isZero();
    }

    @Test
    @DisplayName("다시 보내도 소용없는 실패는 한 번에 실패로 끝낸다")
    void failsImmediatelyOnNonRetryableErrors() {
        Long runId = readyRunFor("gaadmin", GA_ADMIN_PHONE);
        when(smsGateway.send(anyString(), anyString())).thenThrow(new SmsSendException("SignatureDoesNotMatch", false));

        service.dispatchPending();

        assertThat(notifications(runId).get(0)).containsEntry("status", "FAILED").containsEntry("attempt_count", 1)
                .containsEntry("error_code", "SignatureDoesNotMatch");
    }

    @Test
    @DisplayName("문자 키가 설정되지 않았으면 건너뜀으로 남긴다")
    void skipsWhenTheProviderIsNotConfigured() {
        Long runId = readyRunFor("gaadmin", GA_ADMIN_PHONE);
        when(smsGateway.send(anyString(), anyString())).thenThrow(new SmsSendException("NO_API_KEY", false));

        service.dispatchPending();

        assertThat(notifications(runId).get(0)).containsEntry("status", "SKIPPED").containsEntry("error_code", "NO_API_KEY");
    }

    @Test
    @DisplayName("보내기 전에 수신 동의를 철회했으면 보내지 않는다")
    void doesNotSendAfterConsentWasWithdrawn() {
        Long runId = readyRunFor("gaadmin", GA_ADMIN_PHONE);
        jdbc.update("DELETE FROM fgc.user_notification_setting WHERE user_id = ?", userId("gaadmin"));

        service.dispatchPending();

        assertThat(notifications(runId).get(0)).containsEntry("status", "SKIPPED")
                .containsEntry("error_code", "CONSENT_WITHDRAWN");
        verify(smsGateway, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("실패하거나 건너뛴 알림만 다시 보내기로 되돌릴 수 있다")
    void resendsOnlyFailedOrSkippedNotifications() {
        Long runId = readyRunFor("gaadmin", GA_ADMIN_PHONE);
        Long id = (Long) notifications(runId).get(0).get("closing_notification_id");
        when(smsGateway.send(anyString(), anyString())).thenThrow(new SmsSendException("NO_API_KEY", false));
        service.dispatchPending();

        service.resend(id);

        assertThat(notifications(runId).get(0)).containsEntry("status", "PENDING").containsEntry("attempt_count", 0)
                .containsEntry("error_code", null);
        assertThatThrownBy(() -> service.resend(id)).isInstanceOfSatisfying(FgcBusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(FgcErrorCode.NOTI_001));
        assertThatThrownBy(() -> service.resend(-1L)).isInstanceOfSatisfying(FgcBusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(FgcErrorCode.COMMON_004));
    }

    @Test
    @DisplayName("발송 이력은 최신순으로 주고 수신번호는 가운데를 가린다")
    void listsHistoryNewestFirstWithMaskedNumbers() {
        Long runId = readyRunFor("gaadmin", GA_ADMIN_PHONE);

        ClosingNotificationResponse first = service.search(1, 20).content().get(0);

        assertThat(first.validationRunId()).isEqualTo(runId);
        assertThat(first.eventType()).isEqualTo("FINALIZE_READY");
        assertThat(first.validationMonth()).isEqualTo(LocalDate.of(2088, 5, 1));
        assertThat(first.recipientLoginId()).isEqualTo("gaadmin");
        assertThat(first.recipientPhone()).isEqualTo("010-****-0102");
        assertThat(first.status()).isEqualTo("PENDING");
    }

    private Long readyRunFor(String loginId, String phone) {
        Long runId = createCompletedRun(LocalDate.of(2088, 5, 1));
        optIn(loginId, phone);
        service.detectFinalizeReady();
        return runId;
    }

    private void makeDue(Long runId) {
        jdbc.update("""
                UPDATE fgc.closing_notification SET next_attempt_at = clock_timestamp() - interval '1 second'
                 WHERE validation_run_id = ?
                """, runId);
    }

    private void optIn(String loginId, String phone) {
        jdbc.update("INSERT INTO fgc.user_notification_setting (user_id, mobile_phone) VALUES (?, ?)", userId(loginId), phone);
    }

    private Long userId(String loginId) {
        return jdbc.queryForObject("SELECT user_id FROM fgc.app_user WHERE login_id = ?", Long.class, loginId);
    }

    private List<Map<String, Object>> notifications(Long runId) {
        return jdbc.queryForList("""
                SELECT n.closing_notification_id, n.event_type, n.status, n.message_text, n.attempt_count,
                       n.provider_message_id, n.error_code, n.sent_at, u.login_id
                  FROM fgc.closing_notification n
                  LEFT JOIN fgc.app_user u ON u.user_id = n.recipient_user_id
                 WHERE n.validation_run_id = ?
                 ORDER BY n.closing_notification_id
                """, runId);
    }

    private Long createRun(LocalDate month) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type)
                VALUES (?, ?, 'PRE_CONFIRM')
                RETURNING validation_run_id
                """, Long.class, month, ThreadLocalRandom.current().nextInt(1_000_000, 2_000_000));
    }

    private Long createCompletedRun(LocalDate month) {
        Long runId = createRun(month);
        jdbc.update("""
                UPDATE fgc.validation_run SET status = 'RUNNING', current_step = 1, started_at = clock_timestamp()
                 WHERE validation_run_id = ?
                """, runId);
        jdbc.update("""
                UPDATE fgc.validation_run SET status = 'COMPLETED', current_step = 8, completed_at = clock_timestamp()
                 WHERE validation_run_id = ?
                """, runId);
        return runId;
    }
}
