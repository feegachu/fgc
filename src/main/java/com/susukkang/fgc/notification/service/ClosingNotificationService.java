package com.susukkang.fgc.notification.service;

import com.susukkang.fgc.audit.service.AuditLogService;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.notification.dto.ClosingNotificationResponse;
import com.susukkang.fgc.notification.repository.ClosingNotificationRepository;
import com.susukkang.fgc.notification.repository.ClosingNotificationRepository.Claimed;
import com.susukkang.fgc.notification.repository.ClosingNotificationRepository.RunMonth;
import com.susukkang.fgc.notification.sms.SmsGateway;
import com.susukkang.fgc.notification.sms.SmsSendException;
import com.susukkang.fgc.validation.service.ValidationRunFinalizationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 설명 : FUN-089 마감 알림. 월 마감에서 일이 다음 사람에게 넘어가는 두 순간을 문자로 알린다.
 *
 * - 확정 조건 충족(FINALIZE_READY): 예외를 닫는 경로가 여러 곳이라 사건을 받지 않고 10초마다 다시 본다.
 *   충족 여부는 확정 API와 같은 체크리스트가 판정하므로 화면·확정과 기준이 어긋나지 않는다. 실행마다 한 번만 알린다.
 * - 확정(RUN_FINALIZED): 이미 발행되는 확정 사건(IF-EVT-07)을 커밋 뒤에 받아 기록한다. 놓친 사건은 주기에서 다시 찾는다.
 * - 발송은 대기 행을 하나씩 잡아 트랜잭션 밖에서 보낸다. 문자 사업자가 멈춰도 확정과 예외 처리는 그대로 진행된다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-11-17
 */
@Slf4j
@Service
public class ClosingNotificationService {

    /** 세 번 시도하면 자동 재시도를 멈춘다. 그 뒤에는 발송 이력 화면의 다시 보내기로만 보낸다. */
    static final int MAX_ATTEMPTS = 3;
    private static final int MAX_SENDS_PER_RUN = 100;
    private static final String SORT = "createdAt,desc";

    private final ClosingNotificationRepository notifications;
    private final ValidationRunFinalizationService finalization;
    private final SmsGateway smsGateway;
    private final AuditLogService auditLogService;
    private final TransactionTemplate transaction;

    // 테스트는 끄고 detectFinalizeReady()·dispatchPending()을 직접 부른다.
    @Value("${fgc.notification.enabled:true}")
    private boolean enabled;

    public ClosingNotificationService(ClosingNotificationRepository notifications,
                                      ValidationRunFinalizationService finalization, SmsGateway smsGateway,
                                      AuditLogService auditLogService, PlatformTransactionManager transactionManager) {
        this.notifications = notifications;
        this.finalization = finalization;
        this.smsGateway = smsGateway;
        this.auditLogService = auditLogService;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.SECONDS)
    public void runScheduled() {
        if (!enabled) {
            return;
        }
        try {
            detectFinalizeReady();
            recoverMissedFinalized();
            dispatchPending();
        } catch (RuntimeException failure) {
            // 알림은 보조 기능이다. 다음 주기에 다시 본다.
            log.warn("마감 알림 주기 실행 실패", failure);
        }
    }

    /** 확정 조건을 모두 채운 실행을 찾아 확정 권한자에게 보낼 알림을 만들고, 새로 알린 실행 수를 돌려준다. */
    public int detectFinalizeReady() {
        int recorded = 0;
        for (RunMonth run : notifications.findFinalizeReadyCandidates()) {
            try {
                Boolean ready = transaction.execute(status -> {
                    if (!finalization.getChecklist(run.validationRunId()).passed()) {
                        return false;
                    }
                    String text = finalizeReadyText(run.validationMonth());
                    notifications.insertForFinalizers(run.validationRunId(), text);
                    notifications.insertNoRecipientIfEmpty(
                            ClosingNotificationRepository.FINALIZE_READY, run.validationRunId(), text);
                    return true;
                });
                if (Boolean.TRUE.equals(ready)) {
                    recorded++;
                }
            } catch (RuntimeException failure) {
                // 한 실행의 실패가 같은 주기의 다른 실행을 막지 않게 한다. 이 실행은 다음 주기에 다시 본다.
                log.warn("확정 대기 알림 감지 실패 validationRunId={}", run.validationRunId(), failure);
            }
        }
        return recorded;
    }

    /** 확정된 실행의 정산월에 확정 지급이 있는 설계사에게 보낼 알림을 만든다. 같은 실행으로 다시 불러도 늘지 않는다. */
    public void recordRunFinalized(Long validationRunId, LocalDate validationMonth) {
        transaction.executeWithoutResult(status -> {
            String text = runFinalizedText(validationMonth);
            notifications.insertForPaidAgents(validationRunId, validationMonth, text);
            notifications.insertNoRecipientIfEmpty(ClosingNotificationRepository.RUN_FINALIZED, validationRunId, text);
        });
    }

    /**
     * 확정 사건을 놓친 실행(기록 실패, 기록 전 재기동)을 다시 찾아 기록하고, 기록한 실행 수를 돌려준다.
     * 평소에는 ClosingNotificationListener 가 확정 직후에 기록하므로 여기서 찾는 것이 없다.
     */
    public int recoverMissedFinalized() {
        int recovered = 0;
        for (RunMonth run : notifications.findFinalizedWithoutNotification()) {
            try {
                recordRunFinalized(run.validationRunId(), run.validationMonth());
                recovered++;
            } catch (RuntimeException failure) {
                log.warn("확정 알림 복구 실패 validationRunId={}", run.validationRunId(), failure);
            }
        }
        return recovered;
    }

    /** 지금 보낼 수 있는 알림을 하나씩 잡아 보내고 처리한 수를 돌려준다. */
    public int dispatchPending() {
        // 마지막 시도 중에 서버가 멈춰 대기로 남은 알림은 한 번 더 보내지 않는다. 실패로 끝내 다시 보내기로만 되살린다.
        transaction.executeWithoutResult(status -> notifications.failExhausted(MAX_ATTEMPTS));
        int handled = 0;
        while (handled < MAX_SENDS_PER_RUN) {
            // 잡으면서 시도 횟수·다음 시도 시각을 먼저 커밋한다. 발송 중에 멈춰도 그 시각 전에는 다시 잡지 않는다.
            Claimed claimed = transaction.execute(status -> notifications.claimNext(MAX_ATTEMPTS).orElse(null));
            if (claimed == null) {
                break;
            }
            handled++;
            if (!send(claimed)) {
                // 문자 사업자가 답하지 않는다. 남은 알림마다 제한 시간을 다시 쓰면 같은 스케줄러의 다른 예약 작업까지 밀린다.
                break;
            }
        }
        return handled;
    }

    /** 잡은 알림 하나를 처리한다. 문자 사업자가 답하지 않아 이번 주기의 발송을 멈춰야 하면 false 다. */
    private boolean send(Claimed claimed) {
        if (claimed.alreadyFinalized()) {
            // 그사이 그 달이 확정됐다. "확정해 주세요"는 틀린 안내가 된다.
            stop(claimed, "SKIPPED", "ALREADY_FINALIZED");
            return true;
        }
        if (claimed.phone() == null) {
            stop(claimed, "SKIPPED", "CONSENT_WITHDRAWN");
            return true;
        }
        // 외부 호출은 트랜잭션 밖에서 한다. 사업자가 느려도 DB 연결과 행 잠금을 붙잡지 않는다.
        String messageId;
        try {
            messageId = smsGateway.send(claimed.phone(), claimed.messageText());
        } catch (SmsSendException failure) {
            if ("NO_API_KEY".equals(failure.code())) {
                stop(claimed, "SKIPPED", failure.code());
                return true;
            }
            if (!failure.retryable()) {
                stop(claimed, "FAILED", failure.code());
                return true;
            }
            if (claimed.attemptCount() < MAX_ATTEMPTS) {
                transaction.executeWithoutResult(status -> notifications.markRetry(claimed.id(), failure.code()));
            } else {
                stop(claimed, "FAILED", failure.code());
            }
            return false;
        } catch (RuntimeException failure) {
            // 예상하지 못한 오류로 같은 알림을 계속 다시 잡지 않게 실패로 끝낸다. 발송 이력의 다시 보내기로만 되살린다.
            log.warn("마감 알림 발송 중 오류 closingNotificationId={}", claimed.id(), failure);
            stop(claimed, "FAILED", "INTERNAL");
            return true;
        }
        transaction.executeWithoutResult(status -> notifications.markSent(claimed.id(), messageId));
        return true;
    }

    private void stop(Claimed claimed, String status, String errorCode) {
        transaction.executeWithoutResult(tx -> notifications.markStopped(claimed.id(), status, errorCode));
    }

    @Transactional(readOnly = true)
    public PageResponse<ClosingNotificationResponse> search(int page, int size) {
        if (page < 1 || (long) (page - 1) * size > Integer.MAX_VALUE) {
            throw invalidField("page");
        }
        if (size < 1 || size > 100) {
            throw invalidField("size");
        }
        return PageResponse.of(notifications.search((page - 1) * size, size), page, size, notifications.count(), SORT);
    }

    /** 실패했거나 건너뛴 알림을 대기로 되돌린다. 다음 주기에 다시 보낸다. */
    @Transactional
    public void resend(Long id) {
        String status = notifications.findStatusForUpdate(id).orElseThrow(() -> new FgcBusinessException(
                FgcErrorCode.COMMON_004, "closingNotificationId", Map.of("field", "closingNotificationId", "id", id), null));
        if (!"FAILED".equals(status) && !"SKIPPED".equals(status)) {
            throw new FgcBusinessException(FgcErrorCode.NOTI_001, Map.of("status", status));
        }
        notifications.resetToPending(id);
        auditLogService.record(AuditLogService.AuditEvent.builder()
                .actionCode("CLOSING_NOTIFICATION_RESEND")
                .entityType("CLOSING_NOTIFICATION")
                .entityId(String.valueOf(id))
                .before(Map.of("status", status))
                .after(Map.of("status", "PENDING"))
                .reason("마감 알림 다시 보내기")
                .build());
    }

    static String finalizeReadyText(LocalDate month) {
        return "[FGC] %d년 %d월 통합검증이 확정 조건을 모두 채웠습니다. 확정해 주세요."
                .formatted(month.getYear(), month.getMonthValue());
    }

    static String runFinalizedText(LocalDate month) {
        return "[FGC] %d년 %d월 수수료 명세가 확정되었습니다. FGC에서 확인해 주세요."
                .formatted(month.getYear(), month.getMonthValue());
    }

    private static FgcBusinessException invalidField(String field) {
        return new FgcBusinessException(FgcErrorCode.COMMON_002, field, Map.of("field", field), null);
    }
}
