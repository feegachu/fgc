package com.susukkang.fgc.schedule.service;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.schedule.mapper.ScheduleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ScheduleReviewService {

    private final ScheduleMapper scheduleMapper;
    private final TransactionTemplate requiresNewTransaction;

    public ScheduleReviewService(
            ScheduleMapper scheduleMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.scheduleMapper = scheduleMapper;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void registerCapRuleReviewAfterRollback(
            Long contractId,
            PaymentStage paymentStage,
            String description
    ) {
        registerCapReviewAfterRollback(
                contractId,
                paymentStage,
                "POLICY_MISSING",
                "1,200% 룰셋 검토 필요 - " + paymentStage.name(),
                description
        );
    }

    public void registerCapReviewAfterRollback(
            Long contractId,
            PaymentStage paymentStage,
            String exceptionType,
            String title,
            String description
    ) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            persistCapReview(contractId, paymentStage, exceptionType, title, description);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    persistCapReview(contractId, paymentStage, exceptionType, title, description);
                }
            }
        });
    }

    /**
     * 확정 게이트 거절 시 계산근거와 함께 현재 트랜잭션에 검토 케이스를 남긴다.
     * 이 경로는 ScheduleConfirmationRejectedException의 noRollbackFor와 짝을 이룬다.
     */
    public void registerCapReviewBeforeCommit(
            Long contractId,
            PaymentStage paymentStage,
            String exceptionType,
            String title,
            String description
    ) {
        int affectedRows = scheduleMapper.upsertPolicyReviewCase(
                contractId,
                paymentStage,
                exceptionType,
                title,
                description
        );
        if (affectedRows != 1) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_500);
        }
    }

    private void persistCapReview(
            Long contractId,
            PaymentStage paymentStage,
            String exceptionType,
            String title,
            String description
    ) {
        requiresNewTransaction.executeWithoutResult(status -> {
            int affectedRows = scheduleMapper.upsertPolicyReviewCase(
                    contractId,
                    paymentStage,
                    exceptionType,
                    title,
                    description
            );
            if (affectedRows != 1) {
                throw new FgcBusinessException(FgcErrorCode.COMMON_500);
            }
        });
    }
}
