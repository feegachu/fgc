package com.susukkang.fgc.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.susukkang.fgc.assistant.controller.PicoGuideController;
import com.susukkang.fgc.assistant.service.PicoGuideStartService;
import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.common.exception.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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

@WebMvcTest(PicoGuideController.class)
@Import({SecurityConfig.class,GlobalExceptionHandler.class,org.springdoc.core.configuration.SpringDocConfiguration.class,org.springdoc.core.properties.SpringDocConfigProperties.class,org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration.class})
class PicoGuideControllerTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper;
    @MockitoBean PicoGuideStartService service; @MockitoBean FgcMessageResolver messages; @MockitoBean ConstraintErrorCodeResolver constraints;
    @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;
    @MockitoBean com.susukkang.fgc.auth.service.FgcJwtAuthenticationConverter converter;
    String currentRole = "SETTLEMENT";
    @org.junit.jupiter.api.BeforeEach void bearerAuthentication() {
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test-only").header("alg","none").subject("tester").build();
        when(decoder.decode("test-only")).thenReturn(jwt);
        when(converter.convert(any())).thenAnswer(invocation -> org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated("tester",null,java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_"+currentRole))));
    }
    String body="""
{"workflowId":"payment.draft","guideVersion":"1.0","paymentId":"77","clientContextRevision":"r1","prepareToken":null}
""";
    @ParameterizedTest @ValueSource(strings={"SETTLEMENT","SYSTEM_ADMIN","GA_ADMIN","COMPLIANCE"})
    void allowsAuthenticatedInternalRoles(String role) throws Exception {
        currentRole=role;
        mvc.perform(post("/api/v1/ai/pico/guides/prepare").with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_"+role)))
          .header("Authorization","Bearer test-only").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        verify(service).prepare(any(),eq("Bearer test-only"));
    }
    @Test void rejectsAgentUnauthenticatedAndInvalidId() throws Exception {
        mvc.perform(post("/api/v1/ai/pico/guides/prepare").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        currentRole="AGENT";
        mvc.perform(post("/api/v1/ai/pico/guides/start").with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_AGENT"))).header("Authorization","Bearer test-only").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        currentRole="SETTLEMENT";
        mvc.perform(post("/api/v1/ai/pico/guides/prepare").with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SETTLEMENT"))).header("Authorization","Bearer test-only").contentType(MediaType.APPLICATION_JSON).content(body.replace("\"77\"","\"0\""))).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void readGuidePostExceptionDoesNotPermitBusinessWrites() throws Exception {
        currentRole="COMPLIANCE";
        mvc.perform(post("/api/v1/transactions").header("Authorization","Bearer test-only").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void staleStartIsConflictEnvelope() throws Exception {
        when(service.start(any(),anyString())).thenThrow(new FgcBusinessException(FgcErrorCode.IMPL_GUIDE_STATE_STALE));
        mvc.perform(post("/api/v1/ai/pico/guides/start").with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SETTLEMENT"))).header("Authorization","Bearer test-only").contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("FGC-IMPL-GUIDE_STATE_STALE"));
    }
    @Test @EnabledIfEnvironmentVariable(named="FGC_EXPORT_PICO_OPENAPI",matches="true")
    void exportRealGuideControllerSchemaWithoutDatabase() throws Exception {
        var generated=(ObjectNode)mapper.readTree(mvc.perform(get("/v3/api-docs").with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var file=java.nio.file.Path.of("frontend/openapi/schema.json");var existing=(ObjectNode)mapper.readTree(java.nio.file.Files.readString(file));
        for(String action:java.util.List.of("prepare","start")) {
            String endpoint="/api/v1/ai/pico/guides/"+action;org.assertj.core.api.Assertions.assertThat(generated.path("paths").has(endpoint)).isTrue();
            ((ObjectNode)existing.get("paths")).set(endpoint,generated.path("paths").get(endpoint));
        }
        var schemas=(ObjectNode)existing.path("components").path("schemas");
        generated.path("components").path("schemas").fields().forEachRemaining(e->{if(!schemas.has(e.getKey()) || e.getKey().startsWith("PicoGuide") || e.getKey().equals("ApiResponsePicoGuideResponse"))schemas.set(e.getKey(),e.getValue());else org.assertj.core.api.Assertions.assertThat(schemas.get(e.getKey())).isEqualTo(e.getValue());});
        java.nio.file.Files.writeString(file,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(existing)+"\n");
    }
}
