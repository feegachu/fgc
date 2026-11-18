package com.susukkang.fgc.notification.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 설명 : 본인 마감 알림 수신 설정(user_notification_setting). 모든 값은 바인딩한다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-11-17
 */
@Repository
@RequiredArgsConstructor
public class NotificationSettingRepository {

    private final JdbcTemplate jdbc;

    public record Setting(String mobilePhone, OffsetDateTime consentedAt) {
    }

    public Optional<Setting> find(Long userId) {
        return jdbc.query("""
                SELECT mobile_phone, consented_at FROM fgc.user_notification_setting WHERE user_id = ?
                """, (rs, row) -> new Setting(rs.getString(1), rs.getObject(2, OffsetDateTime.class)), userId)
                .stream().findFirst();
    }

    /** 번호가 바뀔 때만 동의 시각을 새로 찍는다. */
    public void upsert(Long userId, String mobilePhone) {
        jdbc.update("""
                INSERT INTO fgc.user_notification_setting AS setting (user_id, mobile_phone) VALUES (?, ?)
                ON CONFLICT (user_id) DO UPDATE
                   SET mobile_phone = EXCLUDED.mobile_phone, consented_at = clock_timestamp()
                 WHERE setting.mobile_phone <> EXCLUDED.mobile_phone
                """, userId, mobilePhone);
    }

    public void delete(Long userId) {
        jdbc.update("DELETE FROM fgc.user_notification_setting WHERE user_id = ?", userId);
    }
}
