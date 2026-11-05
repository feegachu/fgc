package com.susukkang.fgc.ai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.susukkang.fgc.ai.config.LlmProperties;
import com.susukkang.fgc.ai.eval.AnswerEvalHarness.Recorded;
import com.susukkang.fgc.ai.eval.AnswerEvaluator.CaseResult;
import com.susukkang.fgc.ai.eval.AnswerEvaluator.EvalCase;
import com.susukkang.fgc.ai.eval.AnswerEvaluator.RunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : 대표 세트를 실제 모델로 돌려 결과표를 남긴다(TER-011, 비용 발생). FGC_LLM_LIVE=true 일 때만 돈다.
 * 모델은 운영 설정값(FGC_LLM_MODEL 로만 바꿈)을 쓰고, FGC_EVAL_MAX_CALLS 를 넘으면 남은 사례를 '미실행'으로 남긴다.
 * FGC_EVAL_REPEAT 만큼 세트를 되풀이해 실행마다 폴더를 나눈다(모델 답이 매번 달라서다). 호출 상한은 전체 합계다.
 * FGC_EVAL_BASE 에 이전 result.json 을 주면 비교표도 남긴다. 키와 요청 원문은 출력하지 않는다. 절차는 docs/FGC_AI_평가_결과서_v1_0.md.
 */
@EnabledIfEnvironmentVariable(named = "FGC_LLM_LIVE", matches = "true")
class AnswerEvalLiveTest {

    /** 최대 입력 20,000자(글자마다 1토큰으로 봐도 입력 0.003달러)와 출력 1,024토큰(0.0006달러)이면 호출당 0.0036달러, 120회 0.43달러를 넘지 않는다(SRC-060 단가). */
    private static final int DEFAULT_MAX_CALLS = 120;
    private static final int MOST_CALLS_PER_CASE = 2;
    private static final double GPT_4O_MINI_INPUT_USD_PER_MTOK = 0.15;
    private static final double GPT_4O_MINI_OUTPUT_USD_PER_MTOK = 0.60;

    @Test
    void representativeSetAgainstTheConfiguredModel() throws IOException {
        LlmProperties properties = AnswerEvalHarness.configuredProperties();
        assertThat(properties.apiKey() != null && !properties.apiKey().isBlank()).as("OPENAI_API_KEY 설정 여부(값은 출력하지 않는다)").isTrue();
        int maxCalls = Integer.parseInt(System.getenv().getOrDefault("FGC_EVAL_MAX_CALLS", Integer.toString(DEFAULT_MAX_CALLS)));
        int repeat = Integer.parseInt(System.getenv().getOrDefault("FGC_EVAL_REPEAT", "1"));
        String runPrefix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-live";
        List<EvalCase> cases = AnswerEvalHarness.loadCases().stream().filter(EvalCase::representative).toList();
        List<String> blocking = new ArrayList<>();
        try (AnswerEvalHarness harness = AnswerEvalHarness.live(properties)) {
            for (int attempt = 1; attempt <= repeat; attempt++) {
                String runId = repeat == 1 ? runPrefix : runPrefix + "-r" + attempt;
                blocking.addAll(runOnce(harness, cases, runId, properties.model(), maxCalls).blocking());
            }
        }
        assertThat(blocking).as("시연 차단 결함").isEmpty();
    }

    private static RunResult runOnce(AnswerEvalHarness harness, List<EvalCase> cases, String runId, String model, int maxCalls) throws IOException {
        Path dir = Path.of("build/ai-eval/answers", runId);
        int callsBefore = harness.gateway().calls();
        List<CaseResult> results = new ArrayList<>();
        Map<String, Map<String, JsonNode>> answers = new LinkedHashMap<>();
        Map<String, String> verdicts = new LinkedHashMap<>();
        for (EvalCase evalCase : cases) {
            if (harness.gateway().calls() + MOST_CALLS_PER_CASE > maxCalls) {
                results.add(AnswerEvalHarness.notRun(evalCase));
                continue;
            }
            CaseResult result = harness.run(evalCase, null);
            results.add(result);
            answers.put(result.id(), result.modelOutputs());
            verdicts.put(result.id(), result.verdict());
        }
        boolean mini = "gpt-4o-mini".equals(model);
        RunResult run = AnswerEvaluator.summarize(runId, "실제 호출", model, harness.gateway().promptVersion(), harness.gateway().calls() - callsBefore,
                mini ? GPT_4O_MINI_INPUT_USD_PER_MTOK : null, mini ? GPT_4O_MINI_OUTPUT_USD_PER_MTOK : null, results);
        AnswerEvaluator.write(run, dir);
        AnswerEvalHarness.MAPPER.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("recorded-answers.json").toFile(),
                new Recorded(model, run.promptVersion(), AnswerEvalHarness.guideFingerprint(), System.getenv("FGC_EVAL_BASE_COMMIT"), answers, verdicts));
        String base = System.getenv("FGC_EVAL_BASE");
        if (base != null && !base.isBlank()) {
            RunResult previous = AnswerEvalHarness.MAPPER.readValue(Path.of(base).toFile(), RunResult.class);
            Files.writeString(dir.resolve("compare.md"), AnswerEvaluator.compare(previous, run), StandardCharsets.UTF_8);
        }
        System.out.println("TER-011 결과표: " + dir.resolve("result.md").toAbsolutePath() + " / " + run.verdict() + " / 비용(USD) " + run.costUsd());
        return run;
    }
}
