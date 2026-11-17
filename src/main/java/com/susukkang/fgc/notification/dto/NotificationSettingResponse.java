package com.susukkang.fgc.notification.dto;

import java.time.OffsetDateTime;

/**
 * 설명 : IF-API-91·92 본인 마감 알림 수신 설정. 수신 동의하지 않았으면 번호와 동의 시각이 null 이다.
 * smsConfigured 가 false 면 서버에 문자 키가 없어 알림 이력만 남고 문자는 가지 않는다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-11-17
 */
public record NotificationSettingResponse(
        String mobilePhone,
        OffsetDateTime consentedAt,
        boolean smsConfigured) {
}
