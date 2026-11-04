package com.susukkang.fgc.assistant;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.assistant.dto.*;
import com.susukkang.fgc.assistant.service.*;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class CalculationProofServiceTest {
    final CalculationDependencyFingerprint dependencies=mock(CalculationDependencyFingerprint.class);
    final MutableClock clock=new MutableClock();
    final CalculationProofService proofs=new CalculationProofService(dependencies,clock);
    final ObjectMapper mapper=new ObjectMapper();
    @BeforeEach void setup(){owner("one");when(dependencies.current()).thenReturn("source-v1");}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    void owner(String token){var jwt=Jwt.withTokenValue(token).header("alg","none").subject("settler").issuedAt(Instant.EPOCH).expiresAt(Instant.parse("2099-01-01T00:00:00Z")).build();SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,List.of(new SimpleGrantedAuthority("ROLE_SETTLEMENT"))));}
    PaymentCalculationRequest input(long revision){return new PaymentCalculationRequest("assistant.v1",PaymentCalculationRequest.Kind.ATTRIBUTION_BALANCE,PaymentCalculationRequest.Mode.EXISTING,new PaymentCalculationRequest.Scope("77"),null,revision);}
    PaymentCalculationSnapshot result(){return new PaymentCalculationSnapshot("calc","ATTRIBUTION_BALANCE","EXISTING","CALCULATED",1,false,true,"ACTUAL","KRW","DRAFT",Map.of("paymentId","77"),Map.of("amount","100000"),List.of(),null,List.of(),List.of(),new PaymentCalculationSnapshot.Basis(List.of(),new PaymentCalculationSnapshot.Rounding(0,"HALF_UP","EACH_LINE"),"2026-10-01",List.of(),List.of(),List.of()),new PaymentCalculationSnapshot.Recommendation(false,null,null,"검증",List.of()),OffsetDateTime.now(clock),null);}
    @Test void currentTokenRevalidatesWithoutExtendingExpiry(){var r=proofs.issue(input(1),result());var next=proofs.revalidate(new CalculationRevalidationRequest("calc",r.validity().stateToken(),input(1)));assertThat(next.validity()).isEqualTo(r.validity());assertThat(next.amounts()).containsEntry("amount","100000");}
    @Test void dependencyChangeRemovesAmountsAndRecommendation(){var r=proofs.issue(input(1),result());when(dependencies.current()).thenReturn("source-v2");var next=proofs.revalidate(new CalculationRevalidationRequest("calc",r.validity().stateToken(),input(1)));assertThat(next.status()).isEqualTo("STALE");assertThat(next.amounts()).isEmpty();assertThat(next.validity()).isNull();assertThat(next.recommendation().allowed()).isFalse();}
    @Test void fiveMinutesExpiresWithoutSleeping(){var r=proofs.issue(input(1),result());clock.now=clock.now.plusSeconds(300);assertThat(proofs.revalidate(new CalculationRevalidationRequest("calc",r.validity().stateToken(),input(1))).status()).isEqualTo("STALE");}
    @Test void sameUserDifferentJwtCannotUseGrant(){var r=proofs.issue(input(1),result());owner("two");assertThatThrownBy(()->proofs.revalidate(new CalculationRevalidationRequest("calc",r.validity().stateToken(),input(1)))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);}
    @Test void changedInputAndForgedTokenAreRejected(){var r=proofs.issue(input(1),result());assertThatThrownBy(()->proofs.revalidate(new CalculationRevalidationRequest("calc",r.validity().stateToken(),input(2)))).isInstanceOf(FgcBusinessException.class);assertThatThrownBy(()->proofs.revalidate(new CalculationRevalidationRequest("calc","forged",input(1)))).isInstanceOf(FgcBusinessException.class);}
    @Test void calculationCannotBecomeActualPrecheck(){var r=proofs.issue(input(1),result());assertThatThrownBy(()->proofs.verifyPrecheck(77,r.validity().stateToken())).isInstanceOf(FgcBusinessException.class);String token=proofs.issuePrecheck(77);assertThat(proofs.verifyPrecheck(77,token)).isNotNull();assertThatThrownBy(()->proofs.verifyPrecheck(78,token)).isInstanceOf(FgcBusinessException.class);assertThatThrownBy(()->proofs.revalidate(new CalculationRevalidationRequest("calc",token,input(1)))).isInstanceOf(FgcBusinessException.class);when(dependencies.current()).thenReturn("changed");assertThatThrownBy(()->proofs.verifyPrecheck(77,token)).isInstanceOf(FgcBusinessException.class);}
    @Test void businessKeysAreNotNumericallyCollapsed(){var first=new PaymentCalculationRequest("assistant.v1",PaymentCalculationRequest.Kind.PAYMENT_CANDIDATE,PaymentCalculationRequest.Mode.HYPOTHETICAL,null,mapper.createObjectNode().put("sourceBusinessKey","007"),1L);var second=new PaymentCalculationRequest("assistant.v1",first.kind(),first.mode(),null,mapper.createObjectNode().put("sourceBusinessKey","7"),1L);var r=proofs.issue(first,result());assertThatThrownBy(()->proofs.revalidate(new CalculationRevalidationRequest("calc",r.validity().stateToken(),second))).isInstanceOf(FgcBusinessException.class);}
    @Test void hypotheticalBalanceHasNoBusinessReads(){var request=new PaymentCalculationRequest("assistant.v1",PaymentCalculationRequest.Kind.ATTRIBUTION_BALANCE,PaymentCalculationRequest.Mode.HYPOTHETICAL,null,mapper.createObjectNode().put("amount","100"),1L);var r=proofs.issue(request,result());proofs.revalidate(new CalculationRevalidationRequest("calc",r.validity().stateToken(),request));verifyNoInteractions(dependencies);}
    @Test void staleResponseAlsoRemovesBasisAmountsAndOldBlockerMessages() {
        var original=result();
        var basis=new PaymentCalculationSnapshot.Basis(List.of(),original.basis().rounding(),"2026-10-01",List.of(),List.of(),List.of(new PaymentCalculationSnapshot.AttributionLine("1","3","2026-10-01","100000","100000")));
        var withLines=new PaymentCalculationSnapshot(original.calculationId(),original.kind(),original.mode(),original.status(),1,false,true,"ACTUAL","KRW","DRAFT",original.resolvedScope(),original.amounts(),List.of(),false,List.of(new PaymentCalculationSnapshot.Reason("CAP","기존 100000원",null,null)),List.of(),basis,original.recommendation(),original.calculatedAt(),null);
        var issued=proofs.issue(input(1),withLines);when(dependencies.current()).thenReturn("changed");
        var stale=proofs.revalidate(new CalculationRevalidationRequest("calc",issued.validity().stateToken(),input(1)));
        assertThat(stale.basis().lines()).isEmpty();assertThat(stale.blockers()).isEmpty();assertThat(stale.amounts()).isEmpty();
    }
    static class MutableClock extends Clock {Instant now=Instant.parse("2026-11-04T00:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}}
}
