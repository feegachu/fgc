package com.susukkang.fgc.assistant.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.susukkang.fgc.assistant.dto.*;
import com.susukkang.fgc.common.exception.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import java.time.*;
import java.security.SecureRandom;
import java.util.*;

/** Opaque process-local, owner/session-bound grants. No financial or conversation persistence. */
@Service
public class CalculationProofService {
    private final CalculationDependencyFingerprint dependencies;
    private final Clock clock;
    private final SecureRandom random=new SecureRandom();
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    private final Map<String,Grant> grants=new HashMap<>();
    private enum Purpose { CALCULATION, PRECHECK }
    private record Grant(Purpose purpose,String owner,String paymentId,String input,String dependency,Instant issued,Instant expires,PaymentCalculationSnapshot result) {}
    @Autowired public CalculationProofService(CalculationDependencyFingerprint dependencies){this(dependencies,Clock.systemUTC());}
    public CalculationProofService(CalculationDependencyFingerprint dependencies,Clock clock){this.dependencies=dependencies;this.clock=clock;}
    public PaymentCalculationSnapshot issue(PaymentCalculationRequest input,PaymentCalculationSnapshot result) {
        if(!Set.of("CALCULATED","REVIEW_REQUIRED").contains(result.status()))return result;
        Instant now=clock.instant();String fingerprint=inputFingerprint(input);String dependency=dependency(input);
        var grant=new Grant(Purpose.CALCULATION,owner(),null,fingerprint,dependency,now,now.plusSeconds(300),result);
        String token=put(grant);
        return copy(result,result.status(),new PaymentCalculationSnapshot.Validity(token,at(now),at(grant.expires()),fingerprint,dependency),false);
    }
    public PaymentCalculationSnapshot revalidate(CalculationRevalidationRequest request) {
        Grant grant=grant(request.stateToken(),Purpose.CALCULATION);
        if(!grant.result().calculationId().equals(request.calculationId()) || !grant.input().equals(inputFingerprint(request.input())))throw stale();
        if(!clock.instant().isBefore(grant.expires()) || !grant.dependency().equals(dependency(request.input()))) {
            synchronized(grants){grants.remove(request.stateToken());}
            return copy(grant.result(),"STALE",null,true);
        }
        return copy(grant.result(),grant.result().status(),new PaymentCalculationSnapshot.Validity(request.stateToken(),at(grant.issued()),at(grant.expires()),grant.input(),grant.dependency()),false);
    }
    public String issuePrecheck(long paymentId) {
        Instant now=clock.instant();return put(new Grant(Purpose.PRECHECK,owner(),Long.toString(paymentId),null,dependencies.current(),now,now.plusSeconds(300),null));
    }
    public OffsetDateTime verifyPrecheck(long paymentId,String token) {
        Grant grant=grant(token,Purpose.PRECHECK);
        if(!Objects.equals(grant.paymentId(),Long.toString(paymentId)) || !clock.instant().isBefore(grant.expires()) || !grant.dependency().equals(dependencies.current()))throw stale();
        return at(grant.expires());
    }
    private Grant grant(String token,Purpose purpose) {
        Grant grant;synchronized(grants){grant=grants.get(token);}
        if(grant==null || grant.purpose()!=purpose)throw stale();
        if(!grant.owner().equals(owner()))throw new AccessDeniedException("Calculation grant belongs to another account or session");
        return grant;
    }
    private String put(Grant grant) {
        synchronized(grants) {
            grants.entrySet().removeIf(entry->!clock.instant().isBefore(entry.getValue().expires()));
            if(grants.values().stream().filter(value->value.owner().equals(grant.owner())).count()>=32)evictOldest(grant.owner());
            if(grants.size()>=1000)evictOldest(null);
            byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);grants.put(token,grant);return token;
        }
    }
    private void evictOldest(String owner) {
        grants.entrySet().stream().filter(entry->owner==null||entry.getValue().owner().equals(owner))
            .min(java.util.Comparator.comparing(entry->entry.getValue().issued())).map(Map.Entry::getKey).ifPresent(grants::remove);
    }
    private String dependency(PaymentCalculationRequest input) {
        return input.kind()==PaymentCalculationRequest.Kind.ATTRIBUTION_BALANCE && input.mode()==PaymentCalculationRequest.Mode.HYPOTHETICAL?"NO_BUSINESS_DEPENDENCIES":dependencies.current();
    }
    private String inputFingerprint(PaymentCalculationRequest request){return PicoFingerprint.of(canonical(mapper.valueToTree(request)).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private JsonNode canonical(JsonNode value) {
        if(value.isObject()){ObjectNode sorted=mapper.createObjectNode();var names=new ArrayList<String>();value.fieldNames().forEachRemaining(names::add);Collections.sort(names);for(String name:names)sorted.set(name,canonical(value.get(name)));return sorted;}
        if(value.isArray()){ArrayNode array=mapper.createArrayNode();value.forEach(item->array.add(canonical(item)));return array;}
        return value;
    }
    private String owner() {
        var auth=SecurityContextHolder.getContext().getAuthentication();if(auth==null || !auth.isAuthenticated())throw new AccessDeniedException("Authenticated calculation owner required");
        String session;
        if(auth.getDetails() instanceof com.susukkang.fgc.auth.service.FgcJwtAuthenticationConverter.SessionIdentity identity)session=identity.id();
        else if(auth instanceof JwtAuthenticationToken jwt)session=jwt.getToken().hasClaim("sid")?jwt.getToken().getClaimAsString("sid"):jwt.getToken().getTokenValue();
        else {
            if(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()!=null)throw new AccessDeniedException("Verified login session is required");
            session="internal-test-or-service-principal";
        }
        return PicoFingerprint.of(auth.getName()+"|"+auth.getAuthorities().stream().map(Object::toString).sorted().toList()+"|"+session);
    }
    private PaymentCalculationSnapshot copy(PaymentCalculationSnapshot result,String status,PaymentCalculationSnapshot.Validity validity,boolean stale) {
        boolean allow=!stale && status.equals("CALCULATED") && result.kind().equals("EXPECTED_COMMISSION") && "SINGLE_LINE".equals(result.resolvedScope().get("aggregation")) && result.lines().size()==1 && result.resolvedScope().containsKey("policyVersionId") && !result.basis().ruleIds().isEmpty();
        var recommendation=new PaymentCalculationSnapshot.Recommendation(allow,allow?"payment.amount":null,allow?result.amounts().get("expectedAmount"):null,stale?"계산 근거가 변경되거나 만료됐습니다. 다시 계산해주세요.":result.recommendation().reason(),allow?List.of("VERIFY_ACTUAL_STATEMENT","ACTUAL_PRECHECK_REQUIRED"):result.recommendation().requiredChecks());
        var basis=stale?new PaymentCalculationSnapshot.Basis(result.basis().sourceRefs(),result.basis().rounding(),result.basis().asOfDate(),result.basis().policyVersionIds(),result.basis().ruleIds(),List.of()):result.basis();
        return new PaymentCalculationSnapshot(result.calculationId(),result.kind(),result.mode(),status,result.clientRevision(),result.synthetic(),true,result.evidenceKind(),result.currency(),result.paymentStatus(),result.resolvedScope(),stale?Map.of():result.amounts(),stale?List.of():result.capPreview(),stale?null:result.confirmable(),stale?List.of():result.blockers(),stale?List.of("CURRENT_DEPENDENCIES"):result.missingInputs(),basis,recommendation,result.calculatedAt(),validity,stale?List.of():result.lines());
    }
    private OffsetDateTime at(Instant instant){return OffsetDateTime.ofInstant(instant,ZoneOffset.UTC);}
    private static FgcBusinessException stale(){return new FgcBusinessException(FgcErrorCode.ASST_STALE_CONTEXT);}
}
