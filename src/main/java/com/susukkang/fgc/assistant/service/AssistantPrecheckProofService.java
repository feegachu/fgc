package com.susukkang.fgc.assistant.service;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.transaction.dto.TransactionPrecheckResponse;
import com.susukkang.fgc.transaction.service.CommissionPaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.*;
import java.time.OffsetDateTime;
@Service
@Slf4j
@RequiredArgsConstructor
public class AssistantPrecheckProofService {
    private final CommissionPaymentService payments;
    private final CalculationProofService proofs;
    private final PlatformTransactionManager transactions;
    public record Checked(TransactionPrecheckResponse result,String token) {}
    public record Verification(String paymentId,boolean verified,OffsetDateTime expiresAt) {}
    @PreAuthorize(Roles.CAN_PROCESS)
    public Checked check(long paymentId) {
        var snapshot = new TransactionTemplate(transactions);
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var proofAttempted = new AtomicBoolean();
        try {
            return snapshot.execute(status -> {
                var result = payments.precheck(paymentId);
                if (!result.confirmable() || !result.blockers().isEmpty()) return new Checked(result, null);
                proofAttempted.set(true);
                return new Checked(result, proofs.issuePrecheck(paymentId));
            });
        } catch (RuntimeException unavailable) {
            // Business validation errors retain their original status. Only optional proof failures degrade.
            if (!proofAttempted.get()) throw unavailable;
            log.warn("Assistant precheck proof unavailable; repeating read-only precheck without token. paymentId={}, failureType={}",
                    paymentId, unavailable.getClass().getSimpleName());
            // The failed PostgreSQL transaction has ended. A fresh snapshot avoids an aborted connection
            // and never returns the earlier result with an unverified proof.
            return snapshot.execute(status -> new Checked(payments.precheck(paymentId), null));
        }
    }
    @PreAuthorize(Roles.CAN_PROCESS)
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Verification verify(long paymentId,String token) {return new Verification(Long.toString(paymentId),true,proofs.verifyPrecheck(paymentId,token));}
}
