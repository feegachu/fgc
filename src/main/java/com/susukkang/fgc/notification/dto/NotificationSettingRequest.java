package com.susukkang.fgc.notification.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 설명 : IF-API-92 본인 마감 알림 수신 설정 저장. consent 가 false 면 번호를 지운다(이때 mobilePhone 은 보지 않는다).
 *
 * @author yslee
 * @version 1.0
 * @since 2026-11-17
 */
public record NotificationSettingRequest(
        String mobilePhone,
        @NotNull Boolean consent) {
}
