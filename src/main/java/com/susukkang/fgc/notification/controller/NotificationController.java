package com.susukkang.fgc.notification.controller;

import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.notification.dto.ClosingNotificationResponse;
import com.susukkang.fgc.notification.dto.NotificationSettingRequest;
import com.susukkang.fgc.notification.dto.NotificationSettingResponse;
import com.susukkang.fgc.notification.service.ClosingNotificationService;
import com.susukkang.fgc.notification.service.NotificationSettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 설명 : FUN-089 마감 알림 문자. 본인 수신 설정(IF-API-91·92)과 발송 이력·다시 보내기(IF-API-93·94).
 *
 * @author yslee
 * @version 1.0
 * @since 2026-11-17
 */
@Tag(name = "마감 알림", description = "월 마감에서 일이 넘어가는 순간을 문자로 알리는 기능의 수신 설정과 발송 이력")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationSettingService settingService;
    private final ClosingNotificationService notificationService;

    @Operation(summary = "본인 마감 알림 수신 설정 조회",
            description = "로그인한 사용자의 휴대폰 번호와 수신 동의 시각을 돌려준다. 동의하지 않았으면 둘 다 null 이다.")
    @GetMapping("/notification-settings/me")
    @PreAuthorize(Roles.CAN_RECEIVE_CLOSING_NOTIFICATION)
    public ApiResponse<NotificationSettingResponse> mySetting(@AuthenticationPrincipal FgcUserDetails principal) {
        return ApiResponse.success(settingService.get(principal.getUserId()));
    }

    @Operation(summary = "본인 마감 알림 수신 설정 저장",
            description = "consent 가 true 면 휴대폰 번호를 저장하고, false 면 번호를 지운다. 사용자 ID는 로그인 정보에서만 얻는다.")
    @PutMapping("/notification-settings/me")
    @PreAuthorize(Roles.CAN_RECEIVE_CLOSING_NOTIFICATION)
    public ApiResponse<NotificationSettingResponse> changeMySetting(
            @Valid @RequestBody NotificationSettingRequest request,
            @AuthenticationPrincipal FgcUserDetails principal) {
        return ApiResponse.success(settingService.change(principal.getUserId(), request));
    }

    @Operation(summary = "마감 알림 발송 이력", description = "최신순. 수신번호는 가운데를 가려 돌려준다.")
    @GetMapping("/notifications")
    @PreAuthorize(Roles.CAN_FINALIZE_VALIDATION)
    public ApiResponse<PageResponse<ClosingNotificationResponse>> notifications(
            @Parameter(description = "페이지(1-base)") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "페이지 크기(최대 100)") @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(notificationService.search(page, size));
    }

    @Operation(summary = "마감 알림 다시 보내기",
            description = "실패했거나 건너뛴 알림을 대기로 되돌린다. 다음 발송 주기에 다시 보낸다.")
    @PostMapping("/notifications/{id}/resend")
    @PreAuthorize(Roles.CAN_FINALIZE_VALIDATION)
    public ApiResponse<Void> resend(@PathVariable("id") Long closingNotificationId) {
        notificationService.resend(closingNotificationId);
        return ApiResponse.success();
    }
}
