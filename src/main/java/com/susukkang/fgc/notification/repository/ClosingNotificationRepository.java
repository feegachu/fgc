package com.susukkang.fgc.notification.repository;

import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.notification.dto.ClosingNotificationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 설명 : 마감 알림 대기열 겸 이력(closing_notification). 모든 값은 바인딩한다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-11-17
 */
@Repository
@RequiredArgsConstructor
public class ClosingNotificationRepository {

    public static final String FINALIZE_READY = "FINALIZE_READY";
    public static final String RUN_FINALIZED = "RUN_FINALIZED";

    private final JdbcTemplate jdbc;

    public record RunMonth(Long validationRunId, LocalDate validationMonth) {
    }

    /**
     * 잡은 알림 1건. phone 이 null 이면 그사이 수신 동의를 철회한 것이다.
     * alreadyFinalized 는 확정 대기 알림인데 그사이 그 달이 확정됐다는 뜻이다.
     */
    public record Claimed(Long id, String eventType, Long validationRunId, String phone, String messageText,
                          int attemptCount, boolean alreadyFinalized, boolean eligibleRecipient) {
    }

    /**
     * 확정 조건을 다시 볼 실행. 계산이 끝났고, 그 달에 확정된 실행이 없고, 아직 확정 대기 알림을 만든 적 없는 실행이다.
     * 조건 충족 여부 자체는 확정 API와 같은 체크리스트가 판정한다.
     * 같은 달에 뒤에 만든 MONTHLY·PRE_CONFIRM 실행이 있는 실행은 뺀다. 확정 조건 7(#491)과 같은 기준이라 어차피
     * 확정할 수 없고, 빼지 않으면 다시 돌린 횟수만큼 체크리스트 조회가 10초마다 돈다.
     */
    public List<RunMonth> findFinalizeReadyCandidates() {
        return jdbc.query("""
                SELECT vr.validation_run_id, vr.validation_month
                  FROM fgc.validation_run vr
                 WHERE vr.status = 'COMPLETED'
                   AND vr.run_type IN ('MONTHLY', 'PRE_CONFIRM')
                   AND NOT EXISTS (SELECT 1 FROM fgc.validation_run finalized
                                    WHERE finalized.validation_month = vr.validation_month
                                      AND finalized.status = 'FINALIZED')
                   AND NOT EXISTS (SELECT 1 FROM fgc.validation_run newer
                                    WHERE newer.validation_month = vr.validation_month
                                      AND newer.run_type IN ('MONTHLY', 'PRE_CONFIRM')
                                      AND newer.validation_run_id > vr.validation_run_id)
                   AND NOT EXISTS (SELECT 1 FROM fgc.closing_notification n
                                    WHERE n.validation_run_id = vr.validation_run_id
                                      AND n.event_type = 'FINALIZE_READY')
                 ORDER BY vr.validation_run_id
                """, (rs, row) -> new RunMonth(rs.getLong(1), rs.getObject(2, LocalDate.class)));
    }

    /** 수신 동의한 확정 권한자(GA관리자·시스템관리자)마다 대기 알림을 만든다. */
    public int insertForFinalizers(Long validationRunId, String messageText) {
        return jdbc.update("""
                INSERT INTO fgc.closing_notification (event_type, validation_run_id, recipient_user_id, message_text)
                SELECT 'FINALIZE_READY', ?, u.user_id, ?
                  FROM fgc.app_user u
                  JOIN fgc.app_role r ON r.role_id = u.role_id
                  JOIN fgc.user_notification_setting s ON s.user_id = u.user_id
                 WHERE r.role_code IN ('GA_ADMIN', 'SYSTEM_ADMIN') AND u.account_status = 'ACTIVE'
                ON CONFLICT DO NOTHING
                """, validationRunId, messageText);
    }

    /** 그 정산월에 본인이 받는 확정 지급이 있는 수신 동의 설계사마다 대기 알림을 만든다. */
    public int insertForPaidAgents(Long validationRunId, LocalDate validationMonth, String messageText) {
        return jdbc.update("""
                INSERT INTO fgc.closing_notification (event_type, validation_run_id, recipient_user_id, message_text)
                SELECT 'RUN_FINALIZED', ?, u.user_id, ?
                  FROM fgc.app_user u
                  JOIN fgc.app_role r ON r.role_id = u.role_id
                  JOIN fgc.user_notification_setting s ON s.user_id = u.user_id
                 WHERE r.role_code = 'AGENT' AND u.account_status = 'ACTIVE'
                   AND EXISTS (SELECT 1 FROM fgc.commission_transaction ct
                                WHERE ct.recipient_agent_id = u.agent_id
                                  AND ct.payment_stage = 'GA_TO_FC' AND ct.status = 'CONFIRMED'
                                  AND ct.cashflow_type = 'PAYMENT' AND ct.settlement_month = ?)
                ON CONFLICT DO NOTHING
                """, validationRunId, messageText, validationMonth);
    }

    /** 이 사건으로 만든 행이 하나도 없을 때만 수신 대상 없음 1행을 남긴다. */
    public void insertNoRecipientIfEmpty(String eventType, Long validationRunId, String messageText) {
        jdbc.update("""
                INSERT INTO fgc.closing_notification (event_type, validation_run_id, message_text, status)
                SELECT ?, ?, ?, 'NO_RECIPIENT'
                 WHERE NOT EXISTS (SELECT 1 FROM fgc.closing_notification n
                                    WHERE n.event_type = ? AND n.validation_run_id = ?)
                ON CONFLICT DO NOTHING
                """, eventType, validationRunId, messageText, eventType, validationRunId);
    }

    /**
     * 지금 보낼 알림 하나를 잡으면서 시도 횟수와 다음 시도 시각(1·2분 뒤)을 먼저 기록한다.
     * 호출자가 바로 커밋하므로 발송 중에 멈춰도 그 시각 전에는 같은 알림을 다시 잡지 않는다.
     * 시도 횟수를 다 쓴 알림은 잡지 않는다.
     */
    public Optional<Claimed> claimNext(int maxAttempts) {
        return jdbc.query("""
                WITH next_notification AS (
                    SELECT n.closing_notification_id
                      FROM fgc.closing_notification n
                     WHERE n.status = 'PENDING' AND n.next_attempt_at <= clock_timestamp()
                       AND n.attempt_count < ?
                     ORDER BY n.next_attempt_at, n.closing_notification_id
                     LIMIT 1
                     FOR UPDATE SKIP LOCKED)
                UPDATE fgc.closing_notification claimed
                   SET attempt_count = claimed.attempt_count + 1,
                       next_attempt_at = clock_timestamp() + make_interval(mins => power(2, claimed.attempt_count)::int)
                  FROM next_notification
                 WHERE claimed.closing_notification_id = next_notification.closing_notification_id
                RETURNING claimed.closing_notification_id, claimed.event_type, claimed.validation_run_id,
                          (SELECT s.mobile_phone FROM fgc.user_notification_setting s
                            WHERE s.user_id = claimed.recipient_user_id),
                          claimed.message_text, claimed.attempt_count,
                          claimed.event_type = 'FINALIZE_READY' AND EXISTS (
                              SELECT 1
                                FROM fgc.validation_run mine
                                JOIN fgc.validation_run finalized
                                  ON finalized.validation_month = mine.validation_month
                               WHERE mine.validation_run_id = claimed.validation_run_id
                                 AND finalized.status = 'FINALIZED'),
                          EXISTS (SELECT 1 FROM fgc.app_user u
                                    JOIN fgc.app_role r ON r.role_id = u.role_id
                                   WHERE u.user_id = claimed.recipient_user_id AND u.account_status = 'ACTIVE'
                                     AND ((claimed.event_type = 'FINALIZE_READY' AND r.role_code IN ('GA_ADMIN', 'SYSTEM_ADMIN'))
                                       OR (claimed.event_type = 'RUN_FINALIZED' AND r.role_code = 'AGENT')))
                """, (rs, row) -> new Claimed(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                        rs.getString(5), rs.getInt(6), rs.getBoolean(7), rs.getBoolean(8)), maxAttempts)
                .stream().findFirst();
    }

    /**
     * 시도 횟수를 다 쓰고도 대기로 남은 알림을 실패로 끝낸다. 마지막 시도를 잡은 뒤 결과를 적기 전에 서버가 멈춘 경우다.
     * 다음 시도 시각이 지난 것만 본다. 그 전이면 다른 서버가 지금 보내는 중일 수 있다.
     */
    public int failExhausted(int maxAttempts) {
        return jdbc.update("""
                UPDATE fgc.closing_notification
                   SET status = 'FAILED', error_code = COALESCE(error_code, 'INTERNAL')
                 WHERE status = 'PENDING' AND attempt_count >= ? AND next_attempt_at <= clock_timestamp()
                """, maxAttempts);
    }

    /**
     * 확정됐는데 확정 통지를 만든 적 없는 실행. 확정 사건의 기록이 실패했거나 기록 전에 서버가 멈춘 경우다.
     * 하루 안에 확정된 것만 본다. 이 기능을 처음 배포하거나 알림을 껐다 켰을 때 오래전에 확정된 달까지 알리지 않기 위해서다.
     */
    public List<RunMonth> findFinalizedWithoutNotification() {
        return jdbc.query("""
                SELECT vr.validation_run_id, vr.validation_month
                  FROM fgc.validation_run vr
                 WHERE vr.status = 'FINALIZED' AND vr.finalized_at >= clock_timestamp() - interval '1 day'
                   AND NOT EXISTS (SELECT 1 FROM fgc.closing_notification n
                                    WHERE n.validation_run_id = vr.validation_run_id
                                      AND n.event_type = 'RUN_FINALIZED')
                 ORDER BY vr.validation_run_id
                """, (rs, row) -> new RunMonth(rs.getLong(1), rs.getObject(2, LocalDate.class)));
    }

    public void markSent(Long id, String providerMessageId) {
        jdbc.update("""
                UPDATE fgc.closing_notification
                   SET status = 'SENT', provider_message_id = ?, error_code = NULL, sent_at = clock_timestamp()
                 WHERE closing_notification_id = ? AND status = 'PENDING'
                """, providerMessageId, id);
    }

    /** 다음 시도를 기다리는 동안 마지막 실패 사유만 남긴다. */
    public void markRetry(Long id, String errorCode) {
        jdbc.update("""
                UPDATE fgc.closing_notification SET error_code = ?
                 WHERE closing_notification_id = ? AND status = 'PENDING'
                """, errorCode, id);
    }

    /** status 는 FAILED 또는 SKIPPED 다. */
    public void markStopped(Long id, String status, String errorCode) {
        jdbc.update("""
                UPDATE fgc.closing_notification SET status = ?, error_code = ?
                 WHERE closing_notification_id = ? AND status = 'PENDING'
                """, status, errorCode, id);
    }

    public Optional<String> findStatusForUpdate(Long id) {
        return jdbc.query("""
                SELECT status FROM fgc.closing_notification WHERE closing_notification_id = ? FOR UPDATE
                """, (rs, row) -> rs.getString(1), id).stream().findFirst();
    }

    public void resetToPending(Long id) {
        jdbc.update("""
                UPDATE fgc.closing_notification
                   SET status = 'PENDING', attempt_count = 0, error_code = NULL, next_attempt_at = clock_timestamp()
                 WHERE closing_notification_id = ?
                """, id);
    }

    public long count() {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM fgc.closing_notification", Long.class);
        return total == null ? 0 : total;
    }

    public List<ClosingNotificationResponse> search(int offset, int size) {
        return jdbc.query("""
                SELECT n.closing_notification_id, n.event_type, n.validation_run_id, vr.validation_month,
                       u.user_name, u.login_id, s.mobile_phone, n.message_text, n.status, n.attempt_count,
                       n.error_code, n.created_at, n.sent_at
                  FROM fgc.closing_notification n
                  JOIN fgc.validation_run vr ON vr.validation_run_id = n.validation_run_id
                  LEFT JOIN fgc.app_user u ON u.user_id = n.recipient_user_id
                  LEFT JOIN fgc.user_notification_setting s ON s.user_id = n.recipient_user_id
                 ORDER BY n.closing_notification_id DESC
                 LIMIT ? OFFSET ?
                """, ClosingNotificationRepository::toResponse, size, offset);
    }

    private static ClosingNotificationResponse toResponse(ResultSet rs, int row) throws SQLException {
        return new ClosingNotificationResponse(rs.getLong(1), rs.getString(2), rs.getLong(3),
                rs.getObject(4, LocalDate.class), rs.getString(5), rs.getString(6), maskPhone(rs.getString(7)),
                rs.getString(8), rs.getString(9), rs.getInt(10), rs.getString(11),
                DateUtil.toSeoul(rs.getObject(12, OffsetDateTime.class)),
                DateUtil.toSeoul(rs.getObject(13, OffsetDateTime.class)));
    }

    /** 01012345678 → 010-****-5678. 가운데 자리는 밖으로 내보내지 않는다. */
    public static String maskPhone(String phone) {
        return phone == null ? null : phone.substring(0, 3) + "-****-" + phone.substring(phone.length() - 4);
    }
}
