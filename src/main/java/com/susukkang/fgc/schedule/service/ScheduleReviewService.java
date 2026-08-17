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
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            persistCapRuleReview(contractId, paymentStage, description);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    persistCapRuleReview(contractId, paymentStage, description);
                }
            }
        });
    }

    private void persistCapRuleReview(
            Long contractId,
            PaymentStage paymentStage,
            String description
    ) {
        requiresNewTransaction.executeWithoutResult(status -> {
            int affectedRows = scheduleMapper.upsertPolicyReviewCase(
                    contractId,
                    paymentStage,
                    "CAP_RULE_MISSING",
                    "1,200% 룰셋 검토 필요 - " + paymentStage.name(),
                    description
            );
            if (affectedRows != 1) {
                throw new FgcBusinessException(FgcErrorCode.COMMON_500);
            }
        });
    }
}
