package com.susukkang.fgc.ai.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.susukkang.fgc.ai.config.LlmProperties;
import com.susukkang.fgc.ai.entity.AiCallLog;
import com.susukkang.fgc.ai.eval.AnswerEvaluator.CaseResult;
import com.susukkang.fgc.ai.eval.AnswerEvaluator.EvalCase;
import com.susukkang.fgc.ai.eval.AnswerEvaluator.Judgement;
import com.susukkang.fgc.ai.eval.AnswerEvaluator.Shown;
import com.susukkang.fgc.ai.eval.AnswerEvaluator.ToolResult;
import com.susukkang.fgc.ai.repository.AiCallLogRepository;
import com.susukkang.fgc.ai.service.EvalLlmGateway;
import com.susukkang.fgc.assistant.dto.PicoAskRequest;
import com.susukkang.fgc.assistant.dto.PicoAskResponse;
import com.susukkang.fgc.assistant.service.PicoAskService;
import com.susukkang.fgc.assistant.service.PicoGuideCatalog;
import com.susukkang.fgc.assistant.service.PicoPaymentContextService;
import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.compare.dto.CompareAskResponse;
import com.susukkang.fgc.compare.dto.CompareRankingResponse;
import com.susukkang.fgc.compare.service.CompareAskService;
import com.susukkang.fgc.compare.service.CompareSummaryService;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * 설명 : 평가 사례를 운영 서비스(PicoAskService·CompareAskService·CompareSummaryService)에 태워 화면에 나갈 답을 얻는다(#639).
 * DB 없이 돈다. ai_call_log 는 저장소 모의에 쌓아 토큰·지연을 읽는다(운영 테이블과 같은 값). HTTP 권한 시험은 PicoAskControllerTest 등이 맡는다.
 */
final class AnswerEvalHarness implements AutoCloseable {

    static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final String RESOURCE_DIR = "ai-eval/answers/";
    /** #833 이 병합되면 나오는 대체 사유. 모델의 1차 판단을 서버가 고정 문장으로 옮긴 경우다. */
    private static final Set<String> MODEL_DECIDED_REASONS = Set.of("OUT_OF_SCOPE", "NEEDS_CLARIFICATION", "NO_MATCHING_GUIDE");

    private final EvalLlmGateway gateway;
    private final PicoAskService pico;
    private final PicoGuideCatalog catalog;
    private final PicoPaymentContextService paymentContext = mock(PicoPaymentContextService.class);
    private final CompareAskService compareAsk;
    private final CompareSummaryService compareSummary;
    private final List<AiCallLog> logRows = new ArrayList<>();
    private final Map<String, CompareRankingResponse> rankings;

    record CaseFile(List<EvalCase> cases, Map<String, CompareRankingResponse> rankings) {
    }

    /** 실제 호출 결과를 재생용으로 남기는 파일. verdicts 는 기록 당시 판정이다. */
    record Recorded(String model, String promptVersion, String baseCommit, Map<String, Map<String, JsonNode>> answers,
                    Map<String, String> verdicts) {
    }

    private AnswerEvalHarness(LlmProperties properties) throws IOException {
        AiCallLogRepository logs = mock(AiCallLogRepository.class);
        when(logs.save(any())).thenAnswer(invocation -> {
            logRows.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        gateway = new EvalLlmGateway(properties, logs, mock(PlatformTransactionManager.class), MAPPER);
        catalog = new PicoGuideCatalog(new ObjectMapper());
        pico = new PicoAskService(gateway, catalog, new ObjectMapper(), paymentContext, properties);
        compareAsk = new CompareAskService(gateway);
        compareSummary = new CompareSummaryService(gateway);
        rankings = readFile("fun-079.json").rankings();
    }

    /** 저장 답변 재생용. API 키 없이 만들어 외부로 나갈 수 없다. */
    static AnswerEvalHarness saved(String model) throws IOException {
        return new AnswerEvalHarness(new LlmProperties("", "https://api.openai.com/v1", model, Duration.ofSeconds(5),
                20_000, 1024, Duration.ofMinutes(10)));
    }

    static AnswerEvalHarness live(LlmProperties properties) throws IOException {
        return new AnswerEvalHarness(properties);
    }

    /** 운영과 같은 설정값을 그대로 읽는다(application.yml + 환경변수). FGC_LLM_MODEL 로 모델만 바꿀 수 있다(OI-023). */
    static LlmProperties configuredProperties() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        ConfigurationPropertySources.attach(environment);
        return Binder.get(environment).bind("fgc.llm", LlmProperties.class).get();
    }

    static List<EvalCase> loadCases() {
        List<EvalCase> cases = new ArrayList<>();
        for (String file : List.of("fun-073.json", "fun-079.json")) {
            cases.addAll(readFile(file).cases());
        }
        return cases;
    }

    static Recorded loadRecorded() {
        try (InputStream in = new ClassPathResource(RESOURCE_DIR + "recorded-answers.json").getInputStream()) {
            return MAPPER.readValue(in, new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CaseFile readFile(String name) {
        try (InputStream in = new ClassPathResource(RESOURCE_DIR + name).getInputStream()) {
            return MAPPER.readValue(in, CaseFile.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** saved 가 null 이면 실제로 호출한다. */
    CaseResult run(EvalCase evalCase, Map<String, JsonNode> saved) {
        gateway.prepare(saved);
        int firstRow = logRows.size();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("eval", "unused",
                List.of(new SimpleGrantedAuthority("ROLE_" + (evalCase.role() == null ? "AGENT" : evalCase.role())))));
        long started = System.nanoTime();
        Shown shown;
        try {
            shown = shown(evalCase);
        } catch (FgcBusinessException e) {
            shown = new Shown(null, null, null, List.of(), null, e.getErrorCode().name());
        } finally {
            SecurityContextHolder.clearContext();
        }
        long wallMs = (System.nanoTime() - started) / 1_000_000;

        ToolResult tool = ToolResult.of(evalCase.ranking() == null ? null : rankings.get(evalCase.ranking()));
        Judgement judgement = AnswerEvaluator.judge(evalCase, shown, tool, gateway.instructions(), allowedFor(evalCase.role()), gateway.caseCalls());
        List<AiCallLog> rows = logRows.subList(firstRow, logRows.size());
        boolean failureExpected = evalCase.expect().fallbackReasons() != null
                && evalCase.expect().fallbackReasons().contains("AI_UNAVAILABLE");
        boolean providerFailed = gateway.caseFailures() > 0;
        String verdict = providerFailed && !failureExpected ? AnswerEvaluator.INCONCLUSIVE
                : judgement.misses().isEmpty() && (judgement.defects().isEmpty() || evalCase.knownGap() != null)
                ? AnswerEvaluator.PASS : AnswerEvaluator.FAIL;
        return new CaseResult(evalCase.id(), evalCase.feature(), evalCase.kind(), evalCase.type(), evalCase.ref(), verdict,
                path(shown, providerFailed), List.copyOf(judgement.defects()), judgement.misses(), evalCase.knownGap(),
                shown.source(), shown.decision(), shown.fallbackReason(), shown.message(), gateway.caseCalls(),
                rows.stream().mapToLong(row -> row.getInputTokens() == null ? 0 : row.getInputTokens()).sum(),
                rows.stream().mapToLong(row -> row.getOutputTokens() == null ? 0 : row.getOutputTokens()).sum(),
                wallMs, rows.stream().map(AiCallLog::getLatencyMs).filter(java.util.Objects::nonNull).toList(),
                Map.copyOf(gateway.outputs()));
    }

    /** 호출 수 상한에 걸려 돌리지 않은 사례의 결과. */
    static CaseResult notRun(EvalCase evalCase) {
        return new CaseResult(evalCase.id(), evalCase.feature(), evalCase.kind(), evalCase.type(), evalCase.ref(), AnswerEvaluator.NOT_RUN,
                "-", List.of(), List.of(), null, null, null, null, null, 0, 0, 0, 0, List.of(), Map.of());
    }

    private Shown shown(EvalCase evalCase) {
        if ("FUN-073".equals(evalCase.feature())) {
            PicoAskResponse response = pico.ask(picoRequest(evalCase));
            return new Shown(response.message(), response.source().name(), response.decision().name(),
                    response.evidenceRefs(), response.fallbackReason(), null);
        }
        CompareRankingResponse ranking = evalCase.ranking() == null ? null : rankings.get(evalCase.ranking());
        if ("EXPLAIN".equals(evalCase.kind())) {
            // 요약이 비면 호출자(CompareRankingService)가 서버 규칙 문장으로 대체한다.
            return compareSummary.summarize(ranking)
                    .map(text -> new Shown(text, "LLM", null, List.of(), null, null))
                    .orElseGet(() -> new Shown(null, "RULE", null, List.of(), null, null));
        }
        CompareAskResponse response = compareAsk.ask(evalCase.question(), ranking);
        return new Shown(response.answer(), response.source(), null, List.of(), null, null);
    }

    private PicoAskRequest picoRequest(EvalCase evalCase) {
        List<PicoAskRequest.EntityRef> refs = List.of();
        if (evalCase.paymentId() != null) {
            refs = List.of(new PicoAskRequest.EntityRef(PicoAskRequest.EntityType.PAYMENT, evalCase.paymentId()));
            reset(paymentContext);
            List<PicoPaymentContextService.Snapshot> states = evalCase.paymentStates().stream()
                    .map(state -> new PicoPaymentContextService.Snapshot(CommissionPaymentStatus.valueOf(state))).toList();
            var stubbing = when(paymentContext.read(evalCase.paymentId())).thenReturn(states.getFirst());
            for (var state : states.subList(1, states.size())) {
                stubbing = stubbing.thenReturn(state);
            }
        }
        return new PicoAskRequest(evalCase.question(), evalCase.screenId(), refs, "eval-" + evalCase.id(),
                evalCase.guideVersion() == null ? "1.0" : evalCase.guideVersion(),
                evalCase.conversation() == null ? List.of() : evalCase.conversation());
    }

    /** 이 역할이 볼 수 있는 등록 근거인가. 운영 코드와 따로 계산해 서버가 권한 밖 근거를 내보내면 드러나게 한다. */
    private Predicate<PicoAskResponse.Evidence> allowedFor(String role) {
        return evidence -> catalog.definitions().stream()
                .filter(guide -> guide.workflowId().equals(evidence.workflowId()) && guide.roles().contains(role))
                .anyMatch(guide -> evidence.stepId() == null || evidence.stepId().startsWith("help.")
                        || catalog.allowedSteps(guide, List.of(role)).stream().anyMatch(step -> step.stepId().equals(evidence.stepId())));
    }

    private String path(Shown shown, boolean providerFailed) {
        if (shown.errorCode() != null || gateway.caseCalls() == 0) {
            return AnswerEvaluator.PATH_SERVER;
        }
        if (providerFailed) {
            return AnswerEvaluator.PATH_PROVIDER_FAILURE;
        }
        return switch (shown.source()) {
            case "AI", "LLM" -> AnswerEvaluator.PATH_AI;
            case "OUT_OF_SCOPE" -> AnswerEvaluator.PATH_MODEL_REFUSED;
            case "FIXED" -> {
                String reason = String.valueOf(shown.fallbackReason());
                yield reason.startsWith("INVALID_AI") ? AnswerEvaluator.PATH_GUARD
                        : MODEL_DECIDED_REASONS.contains(reason) ? AnswerEvaluator.PATH_MODEL_REFUSED : AnswerEvaluator.PATH_SERVER;
            }
            // 079 규칙 문장: 모델이 범위 밖으로 답했으면 모델 판단, 아니면 서버 검사가 답을 버린 것이다.
            default -> gateway.outputs().containsKey("Output") && !gateway.outputs().get("Output").path("inScope").asBoolean(true)
                    ? AnswerEvaluator.PATH_MODEL_REFUSED : AnswerEvaluator.PATH_GUARD;
        };
    }

    EvalLlmGateway gateway() {
        return gateway;
    }

    @Override
    public void close() {
        gateway.close();
    }
}
