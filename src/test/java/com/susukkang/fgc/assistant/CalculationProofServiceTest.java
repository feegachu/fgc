package com.susukkang.fgc.assistant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.assistant.dto.*;
import com.susukkang.fgc.assistant.service.*;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CalculationProofServiceTest {
  final CalculationDependencyFingerprint dependencies =
      mock(CalculationDependencyFingerprint.class);
  final MutableClock clock = new MutableClock();
  final CalculationProofService proofs = new CalculationProofService(dependencies, clock);
  final ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void setup() {
    owner("one");
    when(dependencies.current(any(PaymentCalculationRequest.class))).thenReturn("source-v1");
    when(dependencies.forPayment(anyLong())).thenCallRealMethod();
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  void owner(String token) {
    var jwt =
        Jwt.withTokenValue(token)
            .header("alg", "none")
            .subject("settler")
            .issuedAt(Instant.EPOCH)
            .expiresAt(Instant.parse("2099-01-01T00:00:00Z"))
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority("ROLE_SETTLEMENT"))));
  }

  PaymentCalculationRequest input(long revision) {
    return new PaymentCalculationRequest(
        "assistant.v1",
        PaymentCalculationRequest.Kind.ATTRIBUTION_BALANCE,
        PaymentCalculationRequest.Mode.EXISTING,
        new PaymentCalculationRequest.Scope("77"),
        null,
        revision);
  }

  PaymentCalculationSnapshot result() {
    return new PaymentCalculationSnapshot(
        "calc",
        "ATTRIBUTION_BALANCE",
        "EXISTING",
        "CALCULATED",
        1,
        false,
        true,
        "ACTUAL",
        "KRW",
        "DRAFT",
        Map.of("paymentId", "77"),
        Map.of("amount", "100000"),
        List.of(),
        null,
        List.of(),
        List.of(),
        new PaymentCalculationSnapshot.Basis(
            List.of(),
            new PaymentCalculationSnapshot.Rounding(0, "HALF_UP", "EACH_LINE"),
            "2026-10-01",
            List.of(),
            List.of(),
            List.of()),
        new PaymentCalculationSnapshot.Recommendation(false, null, null, "검증", List.of()),
        OffsetDateTime.now(clock),
        null);
  }

  @Test
  void currentTokenRevalidatesWithoutExtendingExpiry() {
    var r = proofs.issue(input(1), result());
    var next =
        proofs.revalidate(
            new CalculationRevalidationRequest("calc", r.validity().stateToken(), input(1)));
    assertThat(next.validity()).isEqualTo(r.validity());
    assertThat(next.amounts()).containsEntry("amount", "100000");
  }

  @Test
  void dependencyChangeRemovesAmountsAndRecommendation() {
    var r = proofs.issue(input(1), result());
    when(dependencies.current(any(PaymentCalculationRequest.class))).thenReturn("source-v2");
    var next =
        proofs.revalidate(
            new CalculationRevalidationRequest("calc", r.validity().stateToken(), input(1)));
    assertThat(next.status()).isEqualTo("STALE");
    assertThat(next.amounts()).isEmpty();
    assertThat(next.validity()).isNull();
    assertThat(next.recommendation().allowed()).isFalse();
  }

  @Test
  void fiveMinutesExpiresWithoutSleeping() {
    var r = proofs.issue(input(1), result());
    clock.now = clock.now.plusSeconds(300);
    assertThat(
            proofs
                .revalidate(
                    new CalculationRevalidationRequest("calc", r.validity().stateToken(), input(1)))
                .status())
        .isEqualTo("STALE");
  }

  @Test
  void sameUserDifferentJwtCannotUseGrant() {
    var r = proofs.issue(input(1), result());
    owner("two");
    var rejectedRequest1 =
        new CalculationRevalidationRequest("calc", r.validity().stateToken(), input(1));
    assertThatThrownBy(() -> proofs.revalidate(rejectedRequest1))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void productionConvertedSessionIdentityBindsGrants() {
    var auth =
        org.springframework.security.authentication.UsernamePasswordAuthenticationToken
            .authenticated("settler", null, List.of(new SimpleGrantedAuthority("ROLE_SETTLEMENT")));
    auth.setDetails(
        new com.susukkang.fgc.auth.service.FgcJwtAuthenticationConverter.SessionIdentity(
            "validated-session-one"));
    SecurityContextHolder.getContext().setAuthentication(auth);
    var r = proofs.issue(input(1), result());
    auth.setDetails(
        new com.susukkang.fgc.auth.service.FgcJwtAuthenticationConverter.SessionIdentity(
            "validated-session-two"));
    var rejectedRequest2 =
        new CalculationRevalidationRequest("calc", r.validity().stateToken(), input(1));
    assertThatThrownBy(() -> proofs.revalidate(rejectedRequest2))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void changedInputAndForgedTokenAreRejected() {
    var r = proofs.issue(input(1), result());
    var rejectedRequest3 =
        new CalculationRevalidationRequest("calc", r.validity().stateToken(), input(2));
    assertThatThrownBy(() -> proofs.revalidate(rejectedRequest3))
        .isInstanceOf(FgcBusinessException.class);
    var rejectedRequest4 = new CalculationRevalidationRequest("calc", "forged", input(1));
    assertThatThrownBy(() -> proofs.revalidate(rejectedRequest4))
        .isInstanceOf(FgcBusinessException.class);
  }

  @Test
  void calculationCannotBecomeActualPrecheck() {
    var r = proofs.issue(input(1), result());
    String calculationToken = r.validity().stateToken();
    assertThatThrownBy(() -> proofs.verifyPrecheck(77, calculationToken))
        .isInstanceOf(FgcBusinessException.class);
    String token = proofs.issuePrecheck(77);
    assertThat(proofs.verifyPrecheck(77, token)).isNotNull();
    assertThatThrownBy(() -> proofs.verifyPrecheck(78, token))
        .isInstanceOf(FgcBusinessException.class);
    var rejectedRequest5 = new CalculationRevalidationRequest("calc", token, input(1));
    assertThatThrownBy(() -> proofs.revalidate(rejectedRequest5))
        .isInstanceOf(FgcBusinessException.class);
    when(dependencies.current(any(PaymentCalculationRequest.class))).thenReturn("changed");
    assertThatThrownBy(() -> proofs.verifyPrecheck(77, token))
        .isInstanceOf(FgcBusinessException.class);
  }

  @Test
  void businessKeysAreNotNumericallyCollapsed() {
    var first =
        new PaymentCalculationRequest(
            "assistant.v1",
            PaymentCalculationRequest.Kind.PAYMENT_CANDIDATE,
            PaymentCalculationRequest.Mode.HYPOTHETICAL,
            null,
            mapper.createObjectNode().put("sourceBusinessKey", "007"),
            1L);
    var second =
        new PaymentCalculationRequest(
            "assistant.v1",
            first.kind(),
            first.mode(),
            null,
            mapper.createObjectNode().put("sourceBusinessKey", "7"),
            1L);
    var r = proofs.issue(first, result());
    var rejectedRequest6 =
        new CalculationRevalidationRequest("calc", r.validity().stateToken(), second);
    assertThatThrownBy(() -> proofs.revalidate(rejectedRequest6))
        .isInstanceOf(FgcBusinessException.class);
  }

  @Test
  void hypotheticalBalanceHasNoBusinessReads() {
    var request =
        new PaymentCalculationRequest(
            "assistant.v1",
            PaymentCalculationRequest.Kind.ATTRIBUTION_BALANCE,
            PaymentCalculationRequest.Mode.HYPOTHETICAL,
            null,
            mapper.createObjectNode().put("amount", "100"),
            1L);
    var r = proofs.issue(request, result());
    proofs.revalidate(
        new CalculationRevalidationRequest("calc", r.validity().stateToken(), request));
    verifyNoInteractions(dependencies);
  }

  @Test
  void previewCapacityCannotBlockAnActualPrecheck() {
    var oldest = proofs.issue(input(1), result());
    for (int i = 0; i < 32; i++) {
      clock.now = clock.now.plusSeconds(1);
      proofs.issue(input(1), result());
    }
    String actual = proofs.issuePrecheck(77);
    assertThat(proofs.verifyPrecheck(77, actual)).isNotNull();
    var rejectedRequest =
        new CalculationRevalidationRequest("calc", oldest.validity().stateToken(), input(1));
    assertThatThrownBy(() -> proofs.revalidate(rejectedRequest))
        .isInstanceOf(FgcBusinessException.class);
  }

  @Test
  void staleResponseAlsoRemovesBasisAmountsAndOldBlockerMessages() {
    var original = result();
    var basis =
        new PaymentCalculationSnapshot.Basis(
            List.of(),
            original.basis().rounding(),
            "2026-10-01",
            List.of(),
            List.of(),
            List.of(
                new PaymentCalculationSnapshot.AttributionLine(
                    "1", "3", "2026-10-01", "100000", "100000")));
    var withLines =
        new PaymentCalculationSnapshot(
            original.calculationId(),
            original.kind(),
            original.mode(),
            original.status(),
            1,
            false,
            true,
            "ACTUAL",
            "KRW",
            "DRAFT",
            original.resolvedScope(),
            original.amounts(),
            List.of(),
            false,
            List.of(new PaymentCalculationSnapshot.Reason("CAP", "기존 100000원", null, null)),
            List.of(),
            basis,
            original.recommendation(),
            original.calculatedAt(),
            null);
    var issued = proofs.issue(input(1), withLines);
    when(dependencies.current(any(PaymentCalculationRequest.class))).thenReturn("changed");
    var stale =
        proofs.revalidate(
            new CalculationRevalidationRequest("calc", issued.validity().stateToken(), input(1)));
    assertThat(stale.basis().lines()).isEmpty();
    assertThat(stale.blockers()).isEmpty();
    assertThat(stale.amounts()).isEmpty();
  }

  @Test
  void onlyExplicitSingleLineExpectedAmountCanBecomeARecommendation() {
    var basis =
        new PaymentCalculationSnapshot.Basis(
            List.of(),
            result().basis().rounding(),
            "2026-10-01",
            List.of("9"),
            List.of("8"),
            List.of());
    var line =
        new PaymentCalculationSnapshot.CalculationLine(
            "7:1",
            "3",
            "4",
            "5",
            1,
            "2026-10-01",
            "2026-10-31",
            "FIXED",
            "100000",
            "FIXED",
            null,
            "100000",
            "8",
            "PLANNED",
            "PAYMENT");
    for (var aggregation : PaymentCalculationRequest.Scope.Aggregation.values()) {
      var request =
          new PaymentCalculationRequest(
              "assistant.v1",
              PaymentCalculationRequest.Kind.EXPECTED_COMMISSION,
              PaymentCalculationRequest.Mode.EXISTING,
              new PaymentCalculationRequest.Scope(
                  null,
                  "3",
                  "2026-10",
                  com.susukkang.fgc.common.code.PaymentStage.GA_TO_FC,
                  1,
                  "4",
                  "5",
                  null,
                  null,
                  aggregation),
              null,
              1L);
      var expected =
          new PaymentCalculationSnapshot(
              "expected-" + aggregation,
              "EXPECTED_COMMISSION",
              "EXISTING",
              "CALCULATED",
              1,
              false,
              true,
              "EXPECTED",
              "KRW",
              null,
              Map.of("policyVersionId", "9", "aggregation", aggregation.name()),
              Map.of("expectedAmount", "100000"),
              List.of(),
              null,
              List.of(),
              List.of(),
              basis,
              result().recommendation(),
              OffsetDateTime.now(clock),
              null,
              List.of(line));
      var issued = proofs.issue(request, expected);
      assertThat(issued.recommendation().allowed())
          .isEqualTo(aggregation == PaymentCalculationRequest.Scope.Aggregation.SINGLE_LINE);
      if (aggregation == PaymentCalculationRequest.Scope.Aggregation.SINGLE_LINE)
        assertThat(issued.recommendation().suggestedAmount()).isEqualTo("100000");
      when(dependencies.current(any(PaymentCalculationRequest.class))).thenReturn("changed-" + aggregation);
      var stale =
          proofs.revalidate(
              new CalculationRevalidationRequest(
                  issued.calculationId(), issued.validity().stateToken(), request));
      assertThat(stale.status()).isEqualTo("STALE");
      assertThat(stale.recommendation().allowed()).isFalse();
      when(dependencies.current(any(PaymentCalculationRequest.class))).thenReturn("source-v1");
    when(dependencies.forPayment(anyLong())).thenReturn("source-v1");
    }
  }

  static class MutableClock extends Clock {
    Instant now = Instant.parse("2026-11-04T00:00:00Z");

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }
}
