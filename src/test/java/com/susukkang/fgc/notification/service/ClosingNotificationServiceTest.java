package com.susukkang.fgc.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.susukkang.fgc.audit.service.AuditLogService;
import com.susukkang.fgc.notification.repository.ClosingNotificationRepository;
import com.susukkang.fgc.notification.repository.ClosingNotificationRepository.Claimed;
import com.susukkang.fgc.notification.repository.ClosingNotificationRepository.RunMonth;
import com.susukkang.fgc.notification.sms.SmsGateway;
import com.susukkang.fgc.notification.sms.SmsSendException;
import com.susukkang.fgc.validation.dto.FinalizeChecklistResponse;
import com.susukkang.fgc.validation.service.ValidationRunFinalizationService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/** FUN-089: 한 건의 실패가 같은 주기의 다른 알림을 막지 않는지 본다. DB 없이 협력 객체만 바꿔 끼운다. */
class ClosingNotificationServiceTest {

    private final ClosingNotificationRepository notifications = mock(ClosingNotificationRepository.class);
    private final ValidationRunFinalizationService finalization = mock(ValidationRunFinalizationService.class);
    private final SmsGateway smsGateway = mock(SmsGateway.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final ClosingNotificationService service = new ClosingNotificationService(notifications, finalization,
            smsGateway, mock(AuditLogService.class), mock(PlatformTransactionManager.class), scheduler);

    @Test
    @DisplayName("FGC-FUN-089 깨우면 10초 주기를 기다리지 않고 지금 한 번 돌게 예약한다")
    void wakeRunsACycleRightAway() {
        ReflectionTestUtils.setField(service, "enabled", true);
        Instant before = Instant.now();

        service.wake();

        ArgumentCaptor<Runnable> cycle = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Instant> startAt = ArgumentCaptor.forClass(Instant.class);
        verify(scheduler).schedule(cycle.capture(), startAt.capture());
        assertThat(startAt.getValue()).isBetween(before, Instant.now());
        verify(notifications, never()).findFinalizeReadyCandidates();

        cycle.getValue().run();

        verify(notifications).findFinalizeReadyCandidates();
        verify(notifications).claimNext(anyInt());
    }

    @Test
    @DisplayName("FGC-FUN-089 깨워 둔 실행이 돌기 전에는 또 예약하지 않고, 돈 뒤에는 다시 깨울 수 있다")
    void wakeDoesNotPileUpCycles() {
        ReflectionTestUtils.setField(service, "enabled", true);

        service.wake();
        service.wake();

        ArgumentCaptor<Runnable> cycle = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, times(1)).schedule(cycle.capture(), any(Instant.class));

        cycle.getValue().run();
        service.wake();

        verify(scheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("FGC-FUN-089 예약 실행을 꺼 두면 깨워도 돌지 않는다")
    void wakeDoesNothingWhenTheScheduleIsOff() {
        service.wake();

        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("한 실행의 확정 조건 확인이 실패해도 다음 실행은 알린다")
    void keepsDetectingWhenOneRunFails() {
        LocalDate month = LocalDate.of(2088, 7, 1);
        when(notifications.findFinalizeReadyCandidates()).thenReturn(List.of(new RunMonth(1L, month), new RunMonth(2L, month)));
        when(finalization.getChecklist(1L)).thenThrow(new IllegalStateException("체크리스트 조회 실패"));
        when(finalization.getChecklist(2L)).thenReturn(new FinalizeChecklistResponse(2L, true, List.of()));

        assertThat(service.detectFinalizeReady()).isEqualTo(1);

        verify(notifications).insertForFinalizers(eq(2L), anyString());
        verify(notifications, never()).insertForFinalizers(eq(1L), anyString());
    }

    @Test
    @DisplayName("발송 중 예상하지 못한 오류가 나면 그 알림만 실패로 끝내고 다음 알림을 보낸다")
    void failsOnlyTheNotificationThatThrewUnexpectedly() {
        when(notifications.claimNext(anyInt())).thenReturn(
                Optional.of(new Claimed(10L, "RUN_FINALIZED", 1L, "01000000102", "[FGC] 첫 문자", 1, false)),
                Optional.of(new Claimed(11L, "RUN_FINALIZED", 1L, "01000000103", "[FGC] 둘째 문자", 1, false)),
                Optional.empty());
        when(smsGateway.send("01000000102", "[FGC] 첫 문자")).thenThrow(new IllegalStateException("서명 실패"));
        when(smsGateway.send("01000000103", "[FGC] 둘째 문자")).thenReturn("M-2");

        assertThat(service.dispatchPending()).isEqualTo(2);

        verify(notifications).markStopped(10L, "FAILED", "INTERNAL");
        verify(notifications).markSent(11L, "M-2");
        verify(notifications, never()).markSent(eq(10L), anyString());
        verify(notifications, never()).markStopped(eq(11L), anyString(), anyString());
        verify(notifications, never()).markRetry(anyLong(), anyString());
    }

    @Test
    @DisplayName("문자 사업자가 답하지 않으면 그 주기의 남은 알림은 잡지 않고 다음 주기로 넘긴다")
    void stopsTheCycleWhenTheProviderDoesNotRespond() {
        when(notifications.claimNext(anyInt())).thenReturn(
                Optional.of(new Claimed(10L, "RUN_FINALIZED", 1L, "01000000102", "[FGC] 첫 문자", 1, false)),
                Optional.of(new Claimed(11L, "RUN_FINALIZED", 1L, "01000000103", "[FGC] 둘째 문자", 1, false)),
                Optional.empty());
        when(smsGateway.send("01000000102", "[FGC] 첫 문자")).thenThrow(new SmsSendException("TIMEOUT", true));

        // 남은 알림마다 제한 시간을 다시 쓰면 같은 스케줄러를 쓰는 다른 예약 작업까지 밀린다.
        assertThat(service.dispatchPending()).isEqualTo(1);

        verify(notifications).markRetry(10L, "TIMEOUT");
        verify(notifications, times(1)).claimNext(anyInt());
        verify(smsGateway, never()).send(eq("01000000103"), anyString());
    }
}
