package com.susukkang.fgc.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.susukkang.fgc.ai.config.LlmProperties;
import com.susukkang.fgc.ai.repository.AiCallLogRepository;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 설명 : TER-011 평가 전용 게이트웨이(#639). 저장 답변이 있으면 밖으로 나가지 않고 그 JSON 을 돌려주고, 없으면 실제 게이트웨이로 호출한다.
 * 호출한 서비스는 운영 코드 그대로라 서버 검사(숫자·단정·근거 ID)는 두 경우 모두 같게 돈다.
 * 호출마다 지시문과 모델 출력을 모아 프롬프트 지문과 재생용 기록을 만든다. 저장 위치의 패키지 전용 멤버(DATA_GUARD, close)를 쓰려고 이 패키지에 둔다.
 */
public class EvalLlmGateway extends LlmGateway implements AutoCloseable {

    /** 저장 답변에서 공급자 장애를 뜻하는 값이자, 호출 실패를 기록할 때 쓰는 값이다. */
    public static final String AI_FAILURE = "AI_001";

    private final ObjectMapper mapper;
    private final Set<String> instructions = new TreeSet<>();
    private Map<String, JsonNode> saved;
    private Map<String, JsonNode> outputs = new LinkedHashMap<>();
    private int calls;
    private int caseCalls;
    private int caseFailures;

    public EvalLlmGateway(LlmProperties properties, AiCallLogRepository logs, PlatformTransactionManager transactions,
                          ObjectMapper mapper) {
        super(properties, logs, transactions);
        this.mapper = mapper;
    }

    /** 사례 하나를 준비한다. saved 가 null 이면 실제로 호출한다. */
    public void prepare(Map<String, JsonNode> saved) {
        this.saved = saved;
        this.outputs = new LinkedHashMap<>();
        this.caseCalls = 0;
        this.caseFailures = 0;
    }

    @Override
    public <T> T call(LlmRequest request, Class<T> outputType) {
        calls++;
        caseCalls++;
        instructions.add(request.purpose() + "\n" + request.instructions());
        try {
            T output = saved == null ? super.call(request, outputType) : replay(outputType);
            outputs.put(outputType.getSimpleName(), mapper.valueToTree(output));
            return output;
        } catch (FgcBusinessException e) {
            caseFailures++;
            outputs.put(outputType.getSimpleName(), TextNode.valueOf(AI_FAILURE));
            throw e;
        }
    }

    private <T> T replay(Class<T> outputType) {
        JsonNode node = saved.get(outputType.getSimpleName());
        if (node == null) {
            throw new IllegalStateException("저장 답변 없음: " + outputType.getSimpleName());
        }
        if (AI_FAILURE.equals(node.asText(null))) {
            throw new FgcBusinessException(FgcErrorCode.AI_001);
        }
        try {
            return mapper.treeToValue(node, outputType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장 답변 형식 오류: " + outputType.getSimpleName(), e);
        }
    }

    /** 이번 실행에 쓴 지시문 전체의 지문. 프롬프트를 고치면 값이 바뀌어 재실행 필요 여부를 알 수 있다. */
    public String promptVersion() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(DATA_GUARD.getBytes(StandardCharsets.UTF_8));
            instructions.forEach(text -> digest.update(text.getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest()).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<String> instructions() {
        return List.copyOf(instructions);
    }

    public Map<String, JsonNode> outputs() {
        return outputs;
    }

    public int calls() {
        return calls;
    }

    public int caseCalls() {
        return caseCalls;
    }

    public int caseFailures() {
        return caseFailures;
    }

    @Override
    public void close() {
        super.close();
    }
}
