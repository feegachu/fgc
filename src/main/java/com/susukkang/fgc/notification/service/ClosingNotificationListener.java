package com.susukkang.fgc.notification.service;

import com.susukkang.fgc.validation.event.ValidationRunFinalized;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 설명 : 확정 사건(IF-EVT-07)의 구독자. 확정이 커밋된 뒤에만 설계사에게 보낼 알림을 남긴다(FUN-089).
 * 알림은 보조 기능이라 기록이 실패해도 예외를 확정 쪽으로 넘기지 않는다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-11-17
 */
@Slf4j
@Component
public class ClosingNotificationListener {

    private final ClosingNotificationService notifications;
    private final TransactionTemplate requiresNew;

    public ClosingNotificationListener(ClosingNotificationService notifications,
                                       PlatformTransactionManager transactionManager) {
        this.notifications = notifications;
        // 커밋 뒤 단계에서는 끝난 확정 트랜잭션이 아직 묶여 있다. 새 트랜잭션을 열어야 기록이 실제로 커밋된다.
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRunFinalized(ValidationRunFinalized event) {
        try {
            requiresNew.executeWithoutResult(status ->
                    notifications.recordRunFinalized(event.validationRunId(), event.validationMonth()));
        } catch (RuntimeException failure) {
            log.warn("확정 알림 기록 실패 validationRunId={}", event.validationRunId(), failure);
        }
    }
}
