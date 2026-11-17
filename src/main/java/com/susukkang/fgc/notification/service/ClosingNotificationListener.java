package com.susukkang.fgc.notification.service;

import com.susukkang.fgc.exceptioncase.event.ExceptionCaseClosed;
import com.susukkang.fgc.validation.event.ValidationRunFinalized;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 설명 : 확정 사건(IF-EVT-07)과 예외 종결 사건(IF-EVT-08)의 구독자(FUN-089).
 * 확정이 커밋된 뒤에만 설계사에게 보낼 알림을 남기고, 예외가 종결되면 확정 조건을 바로 다시 보게 한다.
 * 알림은 보조 기능이라 실패해도 예외를 확정이나 예외 조치 쪽으로 넘기지 않는다.
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
        // 기록한 확정 통지를 10초 주기를 기다리지 않고 보낸다. 기록에 실패했으면 복구 경로가 바로 다시 찾는다.
        wake();
    }

    /** 정산담당자가 마지막 미처리 예외를 닫은 순간 확정 권한자에게 알리려고, 종결이 커밋되자마자 확정 조건을 다시 본다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onExceptionCaseClosed(ExceptionCaseClosed event) {
        wake();
    }

    private void wake() {
        try {
            notifications.wake();
        } catch (RuntimeException failure) {
            log.warn("마감 알림 주기 깨우기 실패", failure);
        }
    }
}
