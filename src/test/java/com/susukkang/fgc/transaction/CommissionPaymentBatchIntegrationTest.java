package com.susukkang.fgc.transaction;

import com.susukkang.fgc.common.code.*;
import com.susukkang.fgc.common.exception.*;
import com.susukkang.fgc.transaction.dto.*;
import com.susukkang.fgc.transaction.service.CommissionPaymentBatchService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

/** No enclosing test transaction: assertions observe real commits and complete rollbacks. */
@SpringBootTest
class CommissionPaymentBatchIntegrationTest {
    @Autowired CommissionPaymentBatchService service;
    @Autowired JdbcTemplate jdbc;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.susukkang.fgc.policy.repository.PolicyVersionRepository policies;
    private final String prefix = "IT-BATCH-" + UUID.randomUUID();
    @org.junit.jupiter.api.BeforeEach
    void authenticateActualActor() {
        var view = jdbc.queryForObject("SELECT user_id, login_id FROM fgc.app_user ORDER BY user_id LIMIT 1", (rs, index) -> {
            var value = new com.susukkang.fgc.auth.dto.AppUserView();
            value.setUserId(rs.getLong("user_id"));
            value.setLoginId(rs.getString("login_id"));
            value.setPasswordHash("{bcrypt}dummy");
            value.setUserName(value.getLoginId());
            value.setRoleCode("SETTLEMENT");
            return value;
        });
        var principal = new com.susukkang.fgc.auth.dto.FgcUserDetails(view, true, true);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                        principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clean() {
        // The isolated test database is disposable; append-only audit records must remain intact.
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test void secondFailureRollsBackPaymentAttributionAndAudit() {
        var before = counts();
        var valid = request("-1", 1L, PaymentStage.GA_TO_FC, LocalDate.of(2026,7,1), itemId(), 1);
        var invalid = request("-2", 1L, PaymentStage.GA_TO_FC, LocalDate.of(2026,7,1), Long.MAX_VALUE, 1);
        assertThatThrownBy(() -> service.create(new CommissionPaymentBatchRequest(List.of(valid, invalid))))
                .isInstanceOf(FgcBusinessException.class);
        assertThat(counts()).isEqualTo(before);
    }

    @Test void duplicateInOneBatchRollsBackEverything() {
        var before = counts();
        var request = request("-same", 1L, PaymentStage.GA_TO_FC, LocalDate.of(2026,7,1), itemId(), 1);
        assertThatThrownBy(() -> service.create(new CommissionPaymentBatchRequest(List.of(request, request))))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .satisfies(error -> assertThat(new ConstraintErrorCodeResolver().resolve(error)).contains(FgcErrorCode.TRAN_001));
        assertThat(counts()).isEqualTo(before);
    }

    @Test void retryDoesNotDuplicateCommittedDrafts() {
        var request = new CommissionPaymentBatchRequest(List.of(request("-retry", 1L, PaymentStage.GA_TO_FC, LocalDate.of(2026,7,1), itemId(), 1)));
        assertThat(service.create(request)).singleElement().satisfies(row -> assertThat(row.status()).isEqualTo(CommissionPaymentStatus.DRAFT));
        var after = counts();
        assertThatThrownBy(() -> service.create(request)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .satisfies(error -> assertThat(new ConstraintErrorCodeResolver().resolve(error)).contains(FgcErrorCode.TRAN_001));
        assertThat(counts()).isEqualTo(after);
    }

    @Test void mixedScopeAndMultipleAttributionsNeverWrite() {
        var before = counts();
        var first = request("-first", 1L, PaymentStage.GA_TO_FC, LocalDate.of(2026,7,1), itemId(), 1);
        var invalids = List.of(
            request("-contract",2L,PaymentStage.GA_TO_FC,LocalDate.of(2026,7,1),itemId(),1),
            request("-stage",1L,PaymentStage.INSURER_TO_GA,LocalDate.of(2026,7,1),itemId(),1),
            request("-month",1L,PaymentStage.GA_TO_FC,LocalDate.of(2026,8,1),itemId(),1),
            request("-attrs",1L,PaymentStage.GA_TO_FC,LocalDate.of(2026,7,1),itemId(),2));
        for (var invalid : invalids) {
            assertThatThrownBy(() -> service.create(new CommissionPaymentBatchRequest(List.of(first, invalid))))
                .isInstanceOf(FgcBusinessException.class).extracting("errorCode").isEqualTo(FgcErrorCode.COMMON_002);
            assertThat(counts()).isEqualTo(before);
        }
    }

    @Test void missingPolicyStillCommitsDraftsWithoutRollbackOnly() {
        org.mockito.Mockito.doReturn(List.of()).when(policies)
            .findApplicableCurrentCommissionPolicies(1L, PaymentStage.GA_TO_FC);
        var response = service.create(new CommissionPaymentBatchRequest(List.of(
            request("-no-policy",1L,PaymentStage.GA_TO_FC,LocalDate.of(2099,1,1),itemId(),1))));
        var id = response.getFirst().paymentId();
        assertThat(jdbc.queryForObject("SELECT status FROM fgc.commission_transaction WHERE commission_transaction_id=?",String.class,id)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT policy_version_id FROM fgc.commission_transaction WHERE commission_transaction_id=?",Long.class,id)).isNull();
    }

    private List<Long> counts() {
        return List.of(jdbc.queryForObject("SELECT count(*) FROM fgc.commission_transaction",Long.class),
            jdbc.queryForObject("SELECT count(*) FROM fgc.transaction_attribution",Long.class),
            jdbc.queryForObject("SELECT count(*) FROM fgc.audit_log",Long.class));
    }
    private Long itemId() { return jdbc.queryForObject("SELECT commission_item_id FROM fgc.commission_item WHERE item_code='BASE_COMMISSION'",Long.class); }
    private CommissionPaymentCreateRequest request(String key, Long contract, PaymentStage stage, LocalDate month, Long item, int count) {
        var attribution = new CommissionPaymentAttributionRequest(contract,month,BigDecimal.ONE,InclusionDecisionStatus.INCLUDED,
            ExclusionType.NONE,"일괄 테스트", "DIRECT",null,AttributionMethod.DIRECT);
        return new CommissionPaymentCreateRequest("GA_MANUAL_PAYMENT",prefix+key,contract,stage==PaymentStage.GA_TO_FC?6L:null,item,
            BigDecimal.ONE,month,"PAYMENT",month,stage,null,Collections.nCopies(count,attribution),null,"일괄 테스트");
    }
}
