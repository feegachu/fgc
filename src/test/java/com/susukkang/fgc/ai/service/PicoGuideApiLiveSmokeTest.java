package com.susukkang.fgc.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.ai.config.LlmProperties;
import com.susukkang.fgc.ai.entity.AiCallLog;
import com.susukkang.fgc.ai.repository.AiCallLogRepository;
import com.susukkang.fgc.assistant.controller.PicoAskController;
import com.susukkang.fgc.assistant.dto.PicoAskRequest;
import com.susukkang.fgc.assistant.service.PicoAskService;
import com.susukkang.fgc.assistant.service.PicoGuideCatalog;
import com.susukkang.fgc.common.code.AiCallStatus;
import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Explicit opt-in, two synthetic conversational turns (four provider calls) through the secured controller and production service. No database. */
@EnabledIfEnvironmentVariable(named = "FGC_PICO_GUIDE_LIVE_SMOKE", matches = "true")
@WebMvcTest(PicoAskController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, PicoAskService.class,
        PicoGuideCatalog.class, PicoGuideApiLiveSmokeTest.LiveGateway.class})
class PicoGuideApiLiveSmokeTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean AiCallLogRepository logs;
    @MockitoBean com.susukkang.fgc.assistant.service.PicoPaymentContextService paymentContext;
    @MockitoBean PlatformTransactionManager transactions;
    @MockitoBean FgcMessageResolver messages;
    @MockitoBean ConstraintErrorCodeResolver constraints;

    @TestConfiguration(proxyBeanMethods = false)
    static class LiveGateway {
        @Bean LlmGateway gateway(AiCallLogRepository logs, PlatformTransactionManager transactions) {
            String key = System.getenv("OPENAI_API_KEY");
            assertThat(key != null && !key.isBlank()).as("Runner key must be configured; never printed").isTrue();
            return new LlmGateway(new LlmProperties(key, "https://api.openai.com/v1", "gpt-4o-mini",
                    Duration.ofSeconds(30), 20000, 1024, Duration.ofMinutes(10)), logs, transactions);
        }
    }

    @Test
    void securedGuideApiRetrievesApprovedEvidenceAndUnderstandsAFollowup() throws Exception {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        String question = "초안 저장 방법을 알려줘";
        mvc.perform(post("/api/v1/ai/pico/ask").with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_AGENT")))
                .contentType(MediaType.APPLICATION_JSON).content(body(question)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/ai/pico/ask")
                .contentType(MediaType.APPLICATION_JSON).content(body(question)))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(logs);

        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("endpoint", "/api/v1/ai/pico/ask");
        safe.put("checksPassed", false);
        try {
            var reply = ask(question).path("data");
            safe.put("source", reply.path("source").asText());
            safe.put("decision", reply.path("decision").asText());
            safe.put("evidenceCount", reply.path("evidenceRefs").size());
            safe.put("fallbackReason", reply.path("fallbackReason").isNull() ? null : reply.path("fallbackReason").asText());
            assertThat(reply.path("source").asText()).isEqualTo("AI");
            assertThat(reply.path("decision").asText()).isEqualTo("ANSWER_ONLY");
            assertThat(reply.path("contextRevision").asText()).isEqualTo("synthetic-r1");
            assertThat(reply.path("guideVersion").asText()).isEqualTo("1.0");
            assertThat(reply.path("validatedGuide").isNull()).isTrue();
            assertThat(reply.path("message").asText()).contains("초안");
            assertThat(reply.path("evidenceRefs").size()).isBetween(1, 8);
            for (var ref : reply.path("evidenceRefs")) {
                assertThat(new PicoGuideCatalog(mapper).definitions().stream().map(PicoGuideCatalog.Definition::workflowId).toList()).contains(ref.path("workflowId").asText());
                assertThat(ref.path("chunkId").asText()).startsWith(ref.path("workflowId").asText() + ":");
            }
            var history = List.of(new PicoAskRequest.Turn(PicoAskRequest.Role.USER, question),
                    new PicoAskRequest.Turn(PicoAskRequest.Role.ASSISTANT, reply.path("message").asText()));
            var followup = mapper.readTree(mvc.perform(post("/api/v1/ai/pico/ask")
                    .with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SETTLEMENT")))
                    .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(new PicoAskRequest(
                            "그 화면에서 수령인은 어디에서 선택해?", "TRAN-W02", List.of(), "synthetic-r2", "1.0", history))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
            assertThat(followup.path("source").asText()).isEqualTo("AI");
            assertThat(followup.path("decision").asText()).isEqualTo("ANSWER_ONLY");
            assertThat(followup.path("message").asText()).containsAnyOf("수령", "설계사", "수취");
            assertThat(followup.path("evidenceRefs").size()).isBetween(1, 8);
            assertThat(followup.path("validatedGuide").isNull()).isTrue();
            safe.put("followupPassed", true);
            safe.put("checksPassed", true);
        } finally {
            var rows = mockingDetails(logs).getInvocations().stream()
                    .filter(invocation -> invocation.getMethod().getName().equals("save") && invocation.getArguments()[0] instanceof AiCallLog)
                    .map(invocation -> (AiCallLog) invocation.getArguments()[0]).toList();
            safe.put("providerCalls", rows.size());
            try {
                assertThat(rows).hasSize(4);
                var row = rows.getLast();
                safe.put("model", row.getModelName());
                safe.put("status", row.getCallStatus());
                safe.put("errorCode", row.getErrorCode());
                safe.put("inputTokens", rows.stream().map(AiCallLog::getInputTokens).filter(java.util.Objects::nonNull).mapToLong(Number::longValue).sum());
                safe.put("outputTokens", rows.stream().map(AiCallLog::getOutputTokens).filter(java.util.Objects::nonNull).mapToLong(Number::longValue).sum());
                safe.put("latencyMs", rows.stream().map(AiCallLog::getLatencyMs).filter(java.util.Objects::nonNull).mapToLong(Number::longValue).sum());
                assertThat(rows).allSatisfy(log -> assertThat(log.getCallStatus()).isEqualTo(AiCallStatus.SUCCESS));
            } finally {
                var output = Path.of("build/pico-guide-api-smoke/result.json");
                Files.createDirectories(output.getParent());
                Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(safe));
            }
        }
    }

    private String body(String question) throws Exception {
        return mapper.writeValueAsString(new PicoAskRequest(question, "TRAN-W02", List.of(),
                "synthetic-r1", "1.0", List.of()));
    }

    private com.fasterxml.jackson.databind.JsonNode ask(String question) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/v1/ai/pico/ask")
                .with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SETTLEMENT")))
                .contentType(MediaType.APPLICATION_JSON).content(body(question)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
}