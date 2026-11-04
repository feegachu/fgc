package com.susukkang.fgc.assistant.service;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.transaction.dto.TransactionPrecheckResponse;
import com.susukkang.fgc.transaction.service.CommissionPaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.*;
import java.time.OffsetDateTime;
@Service
@RequiredArgsConstructor
public class AssistantPrecheckProofService {
    private final CommissionPaymentService payments;
    private final CalculationProofService proofs;
    public record Checked(TransactionPrecheckResponse result,String token) {}
    public record Verification(String paymentId,boolean verified,OffsetDateTime expiresAt) {}
    @PreAuthorize(Roles.CAN_PROCESS)
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Checked check(long paymentId) {
        var result=payments.precheck(paymentId);
        return new Checked(result,result.confirmable() && result.blockers().isEmpty()?proofs.issuePrecheck(paymentId):null);
    }
    @PreAuthorize(Roles.CAN_PROCESS)
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Verification verify(long paymentId,String token) {return new Verification(Long.toString(paymentId),true,proofs.verifyPrecheck(paymentId,token));}
}
