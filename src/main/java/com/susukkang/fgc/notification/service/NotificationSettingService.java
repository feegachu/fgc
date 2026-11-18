package com.susukkang.fgc.notification.service;

import com.susukkang.fgc.audit.service.AuditLogService;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.notification.dto.NotificationSettingRequest;
import com.susukkang.fgc.notification.dto.NotificationSettingResponse;
import com.susukkang.fgc.notification.repository.ClosingNotificationRepository;
import com.susukkang.fgc.notification.repository.NotificationSettingRepository;
import com.susukkang.fgc.notification.repository.NotificationSettingRepository.Setting;
import com.susukkang.fgc.notification.sms.SmsGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 설명 : FUN-089 본인 마감 알림 수신 설정. 번호는 본인만 등록·삭제하고, 동의를 철회하면 행을 지운다.
 * 감사로그에는 가운데를 가린 번호만 남긴다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-11-17
 */
@Service
@RequiredArgsConstructor
public class NotificationSettingService {

    /** 010·011·016·017·018·019 로 시작하는 10~11자리. 하이픈과 공백은 저장 전에 뺀다. */
    private static final Pattern MOBILE = Pattern.compile("^01[016789][0-9]{7,8}$");

    private final NotificationSettingRepository settings;
    private final SmsGateway smsGateway;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public NotificationSettingResponse get(Long userId) {
        return response(settings.find(userId));
    }

    @Transactional
    public NotificationSettingResponse change(Long userId, NotificationSettingRequest request) {
        Optional<Setting> before = settings.find(userId);
        if (Boolean.TRUE.equals(request.consent())) {
            settings.upsert(userId, normalize(request.mobilePhone()));
        } else {
            settings.delete(userId);
        }
        Optional<Setting> after = settings.find(userId);
        if (!before.map(Setting::mobilePhone).equals(after.map(Setting::mobilePhone))) {
            auditLogService.record(AuditLogService.AuditEvent.builder()
                    .actionCode("NOTIFICATION_SETTING_CHANGED")
                    .entityType("USER_NOTIFICATION_SETTING")
                    .entityId(String.valueOf(userId))
                    .before(auditValue(before))
                    .after(auditValue(after))
                    .reason("마감 알림 수신 설정 변경")
                    .build());
        }
        return response(after);
    }

    private static String normalize(String mobilePhone) {
        String digits = mobilePhone == null ? "" : mobilePhone.replaceAll("[\\s-]", "");
        if (!MOBILE.matcher(digits).matches()) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_002, "mobilePhone", Map.of("field", "mobilePhone"), null);
        }
        return digits;
    }

    private NotificationSettingResponse response(Optional<Setting> setting) {
        return new NotificationSettingResponse(setting.map(Setting::mobilePhone).orElse(null),
                setting.map(value -> DateUtil.toSeoul(value.consentedAt())).orElse(null), smsGateway.isConfigured());
    }

    private static Map<String, Object> auditValue(Optional<Setting> setting) {
        return setting.<Map<String, Object>>map(value -> Map.of(
                        "consented", true, "mobilePhone", ClosingNotificationRepository.maskPhone(value.mobilePhone())))
                .orElseGet(() -> Map.of("consented", false));
    }
}
