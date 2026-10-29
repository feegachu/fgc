package com.susukkang.fgc.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.assistant.dto.*;
import com.susukkang.fgc.assistant.service.*;
import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.exception.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PicoGuideStartServiceTest {
    PicoPaymentContextService payments;
    PicoGuideStartService service;
    MutableClock clock = new MutableClock();
    @BeforeEach void setup() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        payments = mock(PicoPaymentContextService.class);
        service = new PicoGuideStartService(payments, new PicoGuideCatalog(mapper), mapper, clock);
        when(payments.read(77L)).thenReturn(new PicoPaymentContextService.Snapshot(CommissionPaymentStatus.DRAFT,"original"));
        role("settler", "SETTLEMENT");
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    void role(String name, String role) { SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(name,null,List.of(new SimpleGrantedAuthority("ROLE_"+role)))); }
    PicoGuideRequest request(String token) { return new PicoGuideRequest("payment.draft","1.0","77","r1",token); }
    @Test void startsOnlyAfterConsentRechecksAndConsumesOnce() {
        var prepared = service.prepare(request(null),"Bearer session-one");
        assertThat(prepared.prepareToken()).matches("[A-Za-z0-9_-]{43}");
        assertThat(prepared.expiresAt()).isEqualTo(clock.instant().plusSeconds(300));
        var started = service.start(request(prepared.prepareToken()),"Bearer session-one");
        assertThat(started.status()).isEqualTo("STARTED"); assertThat(started.stepId()).isEqualTo("payment.scope"); assertThat(started.prepareToken()).isNull();
        verify(payments,times(2)).read(77L);
        assertStale(() -> service.start(request(prepared.prepareToken()),"Bearer session-one"));
    }
    @Test void rejectsOtherLoginUserRevisionWorkflowOrTamperingWithoutStealingValidGrant() {
        var token = service.prepare(request(null),"Bearer session-one").prepareToken();
        assertStale(() -> service.start(request(token),"Bearer other-login"));
        role("other", "SETTLEMENT"); assertStale(() -> service.start(request(token),"Bearer session-one"));
        role("settler", "SETTLEMENT");
        assertStale(() -> service.start(new PicoGuideRequest("payment.draft","1.0","77","r2",token),"Bearer session-one"));
        assertStale(() -> service.start(new PicoGuideRequest("payment.confirm","1.0","77","r1",token),"Bearer session-one"));
        assertStale(() -> service.start(request("forged"),"Bearer session-one"));
        assertThat(service.start(request(token),"Bearer session-one").status()).isEqualTo("STARTED");
    }
    @Test void changedStoredPaymentOrStatusAndExpiryNeverStart() {
        var token=service.prepare(request(null),"Bearer session-one").prepareToken();
        when(payments.read(77L)).thenReturn(new PicoPaymentContextService.Snapshot(CommissionPaymentStatus.DRAFT,"edited"));
        assertStale(() -> service.start(request(token),"Bearer session-one"));
        for(var status:List.of(CommissionPaymentStatus.CONFIRMED,CommissionPaymentStatus.CANCELLED)) {
            when(payments.read(77L)).thenReturn(new PicoPaymentContextService.Snapshot(status,"original"));
            assertStale(() -> service.prepare(request(null),"Bearer session-one"));
        }
        when(payments.read(77L)).thenReturn(new PicoPaymentContextService.Snapshot(CommissionPaymentStatus.DRAFT,"original"));
        var expiring = service.prepare(request(null),"Bearer session-one").prepareToken();
        clock.now=clock.now.plusSeconds(300);
        assertStale(() -> service.start(request(expiring),"Bearer session-one"));
    }
    @Test void readRolesCannotStartProcessingButCanStartReadGuideAndRoleChangesInvalidateProof() {
        var old=service.prepare(request(null),"Bearer session-one").prepareToken();
        for(String role:List.of("COMPLIANCE","GA_ADMIN")) {
            role("settler",role);
            assertThatThrownBy(() -> service.prepare(request(null),"Bearer session-one")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            var read = new PicoGuideRequest("payment.amount.review","1.0","77","r1",null);
            var token=service.prepare(read,"Bearer session-one").prepareToken();
            assertThat(service.start(new PicoGuideRequest(read.workflowId(),read.guideVersion(),read.paymentId(),read.clientContextRevision(),token),"Bearer session-one").status()).isEqualTo("STARTED");
        }
        role("settler","SYSTEM_ADMIN"); assertStale(() -> service.start(request(old),"Bearer session-one"));
    }
    @Test void concurrentClicksCanConsumeOnlyOnce() throws Exception {
        var token=service.prepare(request(null),"Bearer session-one").prepareToken();
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            Callable<Boolean> attempt=() -> {role("settler","SETTLEMENT");try {gate.await();service.start(request(token),"Bearer session-one");return true;} catch(FgcBusinessException error){return false;} finally{SecurityContextHolder.clearContext();}};
            var a=pool.submit(attempt);var b=pool.submit(attempt);gate.countDown();
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            verify(payments,times(2)).read(77L);
        } finally {pool.shutdownNow();}
    }
    @Test void cacheIsBoundedAndExpiredEntriesAreReclaimed() {
        for(int i=0;i<1000;i++)service.prepare(request(null),"Bearer session-one");
        assertThatThrownBy(() -> service.prepare(request(null),"Bearer session-one"))
            .isInstanceOfSatisfying(FgcBusinessException.class,e -> assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.IMPL_REQUEST_IN_PROGRESS));
        clock.now=clock.now.plusSeconds(300);
        assertThat(service.prepare(request(null),"Bearer session-one").status()).isEqualTo("PREPARED");
    }
    void assertStale(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(FgcBusinessException.class,e -> assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.IMPL_GUIDE_STATE_STALE));
    }
    static class MutableClock extends Clock {
        Instant now=Instant.parse("2026-10-30T00:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;} public Instant instant(){return now;}
    }
}
