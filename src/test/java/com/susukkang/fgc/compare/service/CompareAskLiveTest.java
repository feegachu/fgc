package com.susukkang.fgc.compare.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.susukkang.fgc.ai.config.LlmProperties;
import com.susukkang.fgc.ai.repository.AiCallLogRepository;
import com.susukkang.fgc.ai.service.LlmGateway;
import com.susukkang.fgc.common.code.AiCallPurpose;
import com.susukkang.fgc.compare.dto.CompareAskResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 설명 : 실제 OpenAI 키로 질문 답변 경로를 확인한다. 비용이 들고 키가 필요하므로 FGC_LLM_LIVE=true 일 때만 돈다
 * (일반 CI 제외, llm-live-check 워크플로가 GitHub 시크릿 OPENAI_API_KEY 를 환경변수로 넘겨 실행한다).
 * 키 값은 출력하지 않는다 — 실패 메시지에도 상태 코드와 사유만 남긴다.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "FGC_LLM_LIVE", matches = "true")
class CompareAskLiveTest {

    private static final String MODEL = System.getenv().getOrDefault("FGC_LLM_MODEL", "gpt-4o-mini");

    @Autowired
    private AiCallLogRepository aiCallLogRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private LlmGateway gateway() {
        return new LlmGateway(new LlmProperties(System.getenv("OPENAI_API_KEY"), "https://api.openai.com/v1", MODEL,
                Duration.ofSeconds(60), 20_000, 512, Duration.ofMinutes(10)), aiCallLogRepository, transactionManager);
    }

    /** 키가 OpenAI 에서 받아들여지는지(401·403 이 아닌지)만 본다. */
    @Test
    void keyIsAcceptedByOpenAi() {
        CompareAskService.Output output = gateway().call(LlmGateway.LlmRequest.builder()
                .purpose(AiCallPurpose.RAG_ANSWER)
                .instructions("inScope 를 true 로, answer 를 '확인'으로 답한다.")
                .data("연결 확인")
                .relatedEntityType("COMPARE_ASK_LIVE")
                .build(), CompareAskService.Output.class);

        assertThat(output).isNotNull();
        assertThat(output.answer).isNotBlank();
    }

    /** 개념 질문에 용어 설명 근거로 검증을 통과한 LLM 답변이 온다. */
    @Test
    void conceptQuestionIsAnsweredByLlm() {
        CompareAskResponse response = new CompareAskService(gateway()).ask("이월이 뭐예요?", null);

        assertThat(response.source()).isEqualTo(CompareAskResponse.LLM);
        assertThat(response.answer()).isNotBlank();
    }
}
