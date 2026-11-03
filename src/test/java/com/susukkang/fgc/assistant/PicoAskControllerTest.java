package com.susukkang.fgc.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.susukkang.fgc.assistant.controller.PicoAskController;
import com.susukkang.fgc.assistant.service.PicoAskService;
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

@WebMvcTest(PicoAskController.class)
@Import({SecurityConfig.class,GlobalExceptionHandler.class,org.springdoc.core.configuration.SpringDocConfiguration.class,org.springdoc.core.properties.SpringDocConfigProperties.class,org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration.class})
class PicoAskControllerTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper;
    @MockitoBean PicoAskService service; @MockitoBean FgcMessageResolver messages; @MockitoBean ConstraintErrorCodeResolver constraints;
    String body="""
{"question":"초안 저장 방법","screenId":"TRAN-W02","entityRefs":[],"clientContextRevision":"r1","guideVersion":"1.0","conversation":[]}
""";
    @ParameterizedTest @ValueSource(strings={"SETTLEMENT","SYSTEM_ADMIN","GA_ADMIN","COMPLIANCE"})
    void allowsInternalKnowledgeQueryWithBearer(String role) throws Exception { mvc.perform(post("/api/v1/ai/pico/ask").with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_"+role))).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()); verify(service).ask(any()); }
    @ParameterizedTest @ValueSource(strings={"AGENT","UNKNOWN"})
    void deniesNonInternalRoles(String role) throws Exception { mvc.perform(post("/api/v1/ai/pico/ask").with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_"+role))).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden()); verifyNoInteractions(service); }
    @Test void requiresBearerAndBoundedQuestion() throws Exception {
        mvc.perform(post("/api/v1/ai/pico/ask").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/ai/pico/ask").with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SETTLEMENT"))).contentType(MediaType.APPLICATION_JSON).content(body.replace("초안 저장 방법","x".repeat(2001)))).andExpect(status().isBadRequest()); verifyNoInteractions(service);
    }
    @Test @EnabledIfEnvironmentVariable(named="FGC_EXPORT_PICO_OPENAPI",matches="true")
    void exportRealControllerSchemaWithoutDatabase() throws Exception {
        var generated=(ObjectNode)mapper.readTree(mvc.perform(get("/v3/api-docs").with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String endpoint="/api/v1/ai/pico/ask"; org.assertj.core.api.Assertions.assertThat(generated.path("paths").has(endpoint)).isTrue();
        var file=java.nio.file.Path.of("frontend/openapi/schema.json"); var existing=(ObjectNode)mapper.readTree(java.nio.file.Files.readString(file));
        ((ObjectNode)existing.get("paths")).set(endpoint,generated.path("paths").get(endpoint)); var schemas=(ObjectNode)existing.path("components").path("schemas");
        generated.path("components").path("schemas").fields().forEachRemaining(e->{ if(!schemas.has(e.getKey()) || e.getKey().startsWith("Pico") || e.getKey().equals("ApiResponsePicoAskResponse")) schemas.set(e.getKey(),e.getValue()); else org.assertj.core.api.Assertions.assertThat(schemas.get(e.getKey())).isEqualTo(e.getValue()); });
        java.nio.file.Files.writeString(file,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(existing)+"\n");
    }
    @Test void staleGuideVersionUsesRegisteredConflictEnvelope() throws Exception {
        when(service.ask(any())).thenThrow(new FgcBusinessException(FgcErrorCode.IMPL_GUIDE_VERSION_MISMATCH));
        mvc.perform(post("/api/v1/ai/pico/ask").with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SETTLEMENT")))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("FGC-IMPL-GUIDE_VERSION_MISMATCH"));
    }
    @Test void missingReferencedPaymentUses404Envelope() throws Exception {
        when(service.ask(any())).thenThrow(new FgcBusinessException(FgcErrorCode.COMMON_004,java.util.Map.of("id",77)));
        mvc.perform(post("/api/v1/ai/pico/ask").with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SETTLEMENT"))).contentType(MediaType.APPLICATION_JSON).content(body.replace("\"entityRefs\":[]", "\"entityRefs\":[{\"type\":\"PAYMENT\",\"id\":77}]")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("FGC-COMMON-004"));
    }
}
