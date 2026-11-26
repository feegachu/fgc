package com.susukkang.fgc.notification.sms;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 설명 : fgc.sms.* 설정(IF-EXT-05). 키·비밀키·발신번호는 환경변수로만 받는다.
 * 셋 중 하나라도 비어 있으면 게이트웨이는 외부 호출 없이 문자 발송만 건너뛴다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-11-17
 */
@ConfigurationProperties("fgc.sms")
public record SmsProperties(
        String apiKey,
        String apiSecret,
        String sender,
        String baseUrl,
        Duration timeout) {
}
