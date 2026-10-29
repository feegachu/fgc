package com.susukkang.fgc.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.susukkang.fgc.assistant.controller.PaymentCalculationController;
import com.susukkang.fgc.assistant.service.PaymentCalculationService;
import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.common.exception.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

@WebMvcTest(PaymentCalculationController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class,
        org.springdoc.core.configuration.SpringDocConfiguration.class,
        org.springdoc.core.properties.SpringDocConfigProperties.class,
        org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration.class})
class PaymentCalculationControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean PaymentCalculationService service;
    @MockitoBean FgcMessageResolver messages;
    @MockitoBean ConstraintErrorCodeResolver constraints;
    String body = """
{"contractVersion":"assistant.v1","kind":"ATTRIBUTION_BALANCE","mode":"EXISTING","scope":{"paymentId":"77"},"candidate":null,"clientRevision":1}
""";
    @ParameterizedTest @ValueSource(strings={"COMPLIANCE","GA_ADMIN","SETTLEMENT","SYSTEM_ADMIN"})
    void internalReadRolesCanUseExactReadOnlyPost(String role) throws Exception {
        mvc.perform(post("/api/v1/assistant/calculations").with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_"+role))).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        verify(service).calculate(any());
    }
    @ParameterizedTest @ValueSource(strings={"AGENT","UNKNOWN"})
    void deniesOtherRoles(String role) throws Exception {
        mvc.perform(post("/api/v1/assistant/calculations").with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_"+role))).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden()); verifyNoInteractions(service);
    }
    @Test void rejectUnknownScopeWithoutReadingAnotherEntity() throws Exception {
        mvc.perform(post("/api/v1/assistant/calculations").with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_COMPLIANCE"))).contentType(MediaType.APPLICATION_JSON).content(body.replace("\"paymentId\":\"77\"", "\"paymentId\":\"77\",\"contractId\":\"88\""))).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void bearerAuthenticationRequiredAndBadIdsAreRejected() throws Exception {
        mvc.perform(post("/api/v1/assistant/calculations").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/assistant/calculations").with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_COMPLIANCE"))).contentType(MediaType.APPLICATION_JSON).content(body.replace("\"77\"","\"-1\""))).andExpect(status().isBadRequest()); verifyNoInteractions(service);
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="FGC_EXPORT_ASSISTANT_OPENAPI", matches="true")
    void exportGeneratedAssistantOpenApiFragment() throws Exception {
        String json = mvc.perform(get("/v3/api-docs").with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        ObjectNode generated = (ObjectNode) mapper.readTree(json);
        org.assertj.core.api.Assertions.assertThat(generated.path("paths").has("/api/v1/assistant/calculations")).isTrue();
        var file = java.nio.file.Path.of("frontend/openapi/schema.json");
        ObjectNode existing = (ObjectNode) mapper.readTree(java.nio.file.Files.readString(file));
        ((ObjectNode) existing.get("paths")).set("/api/v1/assistant/calculations", generated.path("paths").get("/api/v1/assistant/calculations"));
        ObjectNode schemas = (ObjectNode) existing.path("components").path("schemas");
        generated.path("components").path("schemas").fields().forEachRemaining(entry -> {
            var owned = java.util.Set.of("PaymentCalculationRequest", "Scope", "ApiResponsePaymentCalculationSnapshot", "PaymentCalculationSnapshot", "Basis", "Source", "Reason", "Recommendation", "Rounding", "JsonNode");
            if (!schemas.has(entry.getKey()) || owned.contains(entry.getKey())) schemas.set(entry.getKey(), entry.getValue());
            else org.assertj.core.api.Assertions.assertThat(schemas.get(entry.getKey())).isEqualTo(entry.getValue());
        });
        java.nio.file.Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(existing) + "\n");
    }

    @Test void serializeRealCalculatorResponseThroughAuthenticatedMvc() throws Exception {
        var payments = mock(com.susukkang.fgc.transaction.service.CommissionPaymentService.class);
        when(payments.get(77L)).thenReturn(PaymentCalculationServiceTest.payment(com.susukkang.fgc.common.code.CommissionPaymentStatus.DRAFT,"100000","60000","30000"));
        var preview = new com.susukkang.fgc.transaction.dto.TransactionPrecheckResponse.CapPreviewItem(3L,"C-3",com.susukkang.fgc.common.code.PaymentStage.GA_TO_FC,"GA→설계사",java.time.LocalDate.of(2026,10,1),100000L,null,null,200000L,0L,100000L,100000L,100000L,"50",com.susukkang.fgc.common.code.CapResultStatus.NORMAL,"정상",9L,null,null,null);
        when(payments.precheck(77L)).thenReturn(new com.susukkang.fgc.transaction.dto.TransactionPrecheckResponse(77L,java.util.List.of(preview),java.util.List.of(),true));
        var actual = new PaymentCalculationService(payments,mapper);
        when(service.calculate(any())).thenAnswer(invocation -> actual.calculate(invocation.getArgument(0)));
        for (String kind : java.util.List.of("ATTRIBUTION_BALANCE","PAYMENT_CANDIDATE")) {
            String json = mvc.perform(post("/api/v1/assistant/calculations").with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SETTLEMENT"))).contentType(MediaType.APPLICATION_JSON).content(body.replace("ATTRIBUTION_BALANCE",kind)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.synthetic").value(false)).andExpect(jsonPath("$.data.snapshotOnly").value(true)).andExpect(jsonPath("$.data.validity").isEmpty()).andReturn().getResponse().getContentAsString();
            if (kind.equals("ATTRIBUTION_BALANCE")) org.assertj.core.api.Assertions.assertThat(mapper.readTree(json).at("/data/amounts/differenceAmount").asText()).isEqualTo("10000");
            String exportDir = System.getenv("FGC_CALC_RESPONSE_EXPORT_DIR");
            if (exportDir != null) { java.nio.file.Files.createDirectories(java.nio.file.Path.of(exportDir)); java.nio.file.Files.writeString(java.nio.file.Path.of(exportDir,kind+".json"),json); }
        }
        verify(payments,times(2)).get(77L); verify(payments).precheck(77L); verifyNoMoreInteractions(payments);
    }
    @Test void missingSourcePaymentIs400WithTheExistingErrorEnvelope() throws Exception {
        when(service.calculate(any())).thenThrow(new FgcBusinessException(FgcErrorCode.COMMON_002, "paymentId", java.util.Map.of("field", "paymentId")));
        mvc.perform(post("/api/v1/assistant/calculations").with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SETTLEMENT"))).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"))
                .andExpect(jsonPath("$.error.field").value("paymentId"));
    }
}
