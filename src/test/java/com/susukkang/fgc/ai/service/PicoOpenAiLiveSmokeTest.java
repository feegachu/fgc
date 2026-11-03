package com.susukkang.fgc.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.ai.config.LlmProperties;
import com.susukkang.fgc.ai.entity.AiCallLog;
import com.susukkang.fgc.ai.repository.AiCallLogRepository;
import com.susukkang.fgc.common.code.AiCallPurpose;
import com.susukkang.fgc.common.code.AiCallStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** One small, opt-in paid request. No Spring context, database or actual customer data. */
@EnabledIfEnvironmentVariable(named = "FGC_PICO_LIVE_SMOKE", matches = "true")
class PicoOpenAiLiveSmokeTest {
    public enum Decision { ANSWER_ONLY }
    public enum Source { PAYMENT_DRAFT_V1 }
    public record Answer(Decision decision, String answer, List<Source> sources) {}
    @Test void oneSyntheticQuestionThroughExistingGateway() throws Exception {
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null || key.isBlank()) key = System.getenv("SPRING_AI_OPENAI_API_KEY");
        assertThat(key != null && !key.isBlank()).as("Server API key is configured; value is never displayed").isTrue();
        var repository = mock(AiCallLogRepository.class);
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var properties = new LlmProperties(key,"https://api.openai.com/v1","gpt-4o-mini",Duration.ofSeconds(30),2000,512,Duration.ofMinutes(10));
        var gateway = new LlmGateway(properties,repository,transactions);
        Answer answer = null;
        try {
            answer = gateway.call(new LlmGateway.LlmRequest(AiCallPurpose.PICO_EXPLAIN,
                    "승인된 가이드만 근거로 한국어 3문장 이내로 설명하세요. 직접 저장하거나 확정했다고 말하지 마세요. 근거 ID를 반환하세요.",
                    "[승인 가이드 PAYMENT_DRAFT_V1] 지급 초안은 대상/지급액/귀속/증빙을 확인하고 사용자가 임시저장 버튼을 직접 누릅니다. 저장 성공은 DRAFT이며 사전검증이나 지급 확정이 아닙니다. [합성 질문] 초안 저장하면 지급까지 끝난 건가요?",null,null),Answer.class);
        } finally {
            gateway.close();
            var rows = ArgumentCaptor.forClass(AiCallLog.class);
            verify(repository, times(1)).save(rows.capture());
            AiCallLog row = rows.getValue();
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("model",row.getModelName()); result.put("status",row.getCallStatus()); result.put("errorCode",row.getErrorCode());
            result.put("inputTokens",row.getInputTokens()); result.put("outputTokens",row.getOutputTokens()); result.put("latencyMs",row.getLatencyMs());
            // Safe synthetic content only; no headers, key, account details or request body.
            if (answer != null) result.put("syntheticAnswer",answer);
            Files.createDirectories(Path.of("build/pico-openai-smoke"));
            Files.writeString(Path.of("build/pico-openai-smoke/result.json"),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
            System.out.println("Pico live smoke: " + row.getCallStatus() + "; inputTokens=" + row.getInputTokens() + "; outputTokens=" + row.getOutputTokens());
            assertThat(row.getCallStatus()).isEqualTo(AiCallStatus.SUCCESS);
        }
        assertThat(answer.decision()).isEqualTo(Decision.ANSWER_ONLY);
        assertThat(answer.sources()).containsExactly(Source.PAYMENT_DRAFT_V1);
        assertThat(answer.answer()).isNotBlank();
    }
}
