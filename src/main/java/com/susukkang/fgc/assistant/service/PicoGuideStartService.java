package com.susukkang.fgc.assistant.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.assistant.dto.*;
import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.exception.*;
import com.susukkang.fgc.common.security.Roles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;

@Service
@PreAuthorize(Roles.ANY_ROLE)
public class PicoGuideStartService {
    private final PicoPaymentContextService payments;
    private final PicoGuideCatalog catalog;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Grant> grants = new HashMap<>();
    private record Grant(String owner, PicoGuideRequest request, String paymentFingerprint,
                         String catalogFingerprint, Instant expiresAt) {}

    @Autowired
    public PicoGuideStartService(PicoPaymentContextService payments, PicoGuideCatalog catalog, ObjectMapper mapper) {
        this(payments, catalog, mapper, Clock.systemUTC());
    }
    public PicoGuideStartService(PicoPaymentContextService payments, PicoGuideCatalog catalog, ObjectMapper mapper, Clock clock) {
        this.payments = payments; this.catalog = catalog; this.mapper = mapper; this.clock = clock;
    }
    public PicoGuideResponse prepare(PicoGuideRequest request, String bearer) {
        String owner = owner(bearer);
        definition(request);
        if (request.prepareToken() != null) throw invalid();
        var snapshot = snapshot(request);
        var expiresAt = clock.instant().plus(Duration.ofMinutes(5));
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        synchronized (grants) {
            purge();
            if (grants.size() >= 1000) throw new FgcBusinessException(FgcErrorCode.IMPL_REQUEST_IN_PROGRESS);
            grants.put(token, new Grant(owner, request, snapshot.fingerprint(), catalogFingerprint(), expiresAt));
        }
        return response("PREPARED", request, expiresAt, token);
    }
    public PicoGuideResponse start(PicoGuideRequest request, String bearer) {
        String owner = owner(bearer);
        definition(request);
        Grant grant;
        synchronized (grants) {
            purge();
            grant = grants.get(request.prepareToken());
            if (grant == null || !grant.owner().equals(owner) || !sameRequest(grant.request(), request)) throw stale();
            // Consume before rereading: duplicate requests or failed verification cannot reuse the grant.
            grants.remove(request.prepareToken());
        }
        var current = snapshot(request);
        if (!grant.paymentFingerprint().equals(current.fingerprint())
                || !grant.catalogFingerprint().equals(catalogFingerprint())
                || !clock.instant().isBefore(grant.expiresAt())) throw stale();
        return response("STARTED", request, grant.expiresAt(), null);
    }
    private PicoPaymentContextService.Snapshot snapshot(PicoGuideRequest request) {
        long id;
        try { id = Long.parseLong(request.paymentId()); if (id <= 0) throw invalid(); }
        catch (NumberFormatException error) { throw invalid(); }
        var value = payments.read(id);
        if (value.status() != CommissionPaymentStatus.DRAFT || value.fingerprint() == null) throw stale();
        return value;
    }
    private void definition(PicoGuideRequest request) {
        if (!"1.0".equals(request.guideVersion())) throw new FgcBusinessException(FgcErrorCode.IMPL_GUIDE_VERSION_MISMATCH);
        var definition = catalog.definitions().stream().filter(d -> d.enabled() && d.workflowId().equals(request.workflowId())).findFirst().orElseThrow(PicoGuideStartService::invalid);
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication.getAuthorities().stream().noneMatch(a -> definition.roles().contains(a.getAuthority().replaceFirst("^ROLE_", "")))) throw new AccessDeniedException("Guide permission denied");
        if (!"payment.scope".equals(definition.steps().getFirst().stepId())) throw stale();
    }
    private String owner(String bearer) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || bearer == null || !bearer.startsWith("Bearer ") || bearer.length() <= 7 || bearer.length() > 8192) throw new AccessDeniedException("Authenticated bearer required");
        var roles = authentication.getAuthorities().stream().map(a -> a.getAuthority()).sorted().toList();
        return PicoFingerprint.of(authentication.getName() + "\n" + roles + "\n" + bearer);
    }
    private String catalogFingerprint() {
        try { return PicoFingerprint.of(mapper.writeValueAsBytes(catalog.definitions())); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Guide catalog cannot be fingerprinted", error); }
    }
    private void purge() { grants.values().removeIf(g -> !clock.instant().isBefore(g.expiresAt())); }
    private static boolean sameRequest(PicoGuideRequest a, PicoGuideRequest b) {
        return Objects.equals(a.workflowId(), b.workflowId()) && Objects.equals(a.guideVersion(), b.guideVersion())
                && Objects.equals(a.paymentId(), b.paymentId()) && Objects.equals(a.clientContextRevision(), b.clientContextRevision());
    }
    private static PicoGuideResponse response(String status, PicoGuideRequest request, Instant expiry, String token) {
        return new PicoGuideResponse(status, request.workflowId(), "payment.scope", request.guideVersion(), request.paymentId(), request.clientContextRevision(), expiry, token);
    }
    private static FgcBusinessException stale() { return new FgcBusinessException(FgcErrorCode.IMPL_GUIDE_STATE_STALE); }
    private static FgcBusinessException invalid() { return new FgcBusinessException(FgcErrorCode.COMMON_002, "guide", Map.of("field", "guide"), "가이드 시작 요청을 확인하세요."); }
}
