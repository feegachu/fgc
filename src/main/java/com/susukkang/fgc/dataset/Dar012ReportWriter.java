package com.susukkang.fgc.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.susukkang.fgc.dataset.Dar012Plan.AnswerRow;
import com.susukkang.fgc.dataset.Dar012Plan.PaymentRow;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** {dataset_id}.json 메타데이터와 {dataset_id}-answers.csv 정답표를 쓴다 (시드명세 §50). 전용 테이블은 만들지 않는다. */
public final class Dar012ReportWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private Dar012ReportWriter() {}

    /** 같은 seed·계약 수·기준일이면 항상 같은 값이 나오는 건수·합계. 생성 시각 같은 비결정 값은 넣지 않는다. */
    public static Map<String, Object> metadata(Dar012Plan plan, int agent01Linked) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("organizations", plan.organizations().size());
        counts.put("agents", plan.agents().size());
        counts.put("activeAgents", plan.agents().stream().filter(a -> "ACTIVE".equals(a.status())).count());
        counts.put("insurerAgentCodes", plan.agentCodes().size());
        counts.put("contracts", plan.contracts().size());
        counts.put("contractStatusEvents", plan.events().size());
        counts.put("financialSnapshots", plan.snapshots().size());
        counts.put("statementBatches", plan.batches().size());
        counts.put("insurerStatementPayments", plan.payments().size());
        counts.put("answerRows", plan.answers().size());

        Map<String, Object> sums = new LinkedHashMap<>();
        sums.put("monthlyEquivalentPremium", plan.contracts().stream()
                .map(Dar012Plan.ContractRow::monthlyEquivalent).reduce(BigDecimal.ZERO, BigDecimal::add).toPlainString());
        sums.put("insurerStatementAmount", plan.payments().stream().map(PaymentRow::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).toPlainString());

        Map<String, Long> byScenario = plan.answers().stream()
                .collect(Collectors.groupingBy(AnswerRow::scenario, TreeMap::new, Collectors.counting()));
        Map<String, Long> byInsurer = plan.contracts().stream()
                .collect(Collectors.groupingBy(Dar012Plan.ContractRow::insurerCode, TreeMap::new, Collectors.counting()));

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("datasetId", plan.datasetId());
        meta.put("generatorVersion", Dar012Planner.GENERATOR_VERSION);
        meta.put("seed", plan.seed());
        meta.put("baseDate", plan.baseDate().toString());
        meta.put("largeGaAssumption", "기준일 직전 분기 일평균 소속 설계사 수 = 600 (프로젝트 가정, OI-024) — 레코드 수·활동 수와 구분한다");
        meta.put("averageAffiliatedAgentsPreviousQuarter", 600);
        meta.put("counts", counts);
        meta.put("sums", sums);
        meta.put("contractsByInsurer", byInsurer);
        meta.put("answersByScenario", byScenario);
        meta.put("agent01LinkedToRepresentative", agent01Linked == 1);
        meta.put("excluded", "스케줄·1,200% 결과·차익거래·분개·대사·예외는 만들지 않는다 — 실제 엔진·배치가 만든다");
        return meta;
    }

    public static Path write(Dar012Plan plan, Path outputDir, boolean agent01Linked) throws IOException {
        Files.createDirectories(outputDir);
        Path json = outputDir.resolve(plan.datasetId() + ".json");
        Files.writeString(json, MAPPER.writeValueAsString(metadata(plan, agent01Linked ? 1 : 0)) + "\n", StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve(plan.datasetId() + "-answers.csv"), answersCsv(plan), StandardCharsets.UTF_8);
        return json;
    }

    /** 검증 결과(배치 시간·파생 건수·정답표 비교·조회 p95). 측정값이라 결정적 메타데이터와 별도 파일에 둔다. */
    public static Path writeVerification(String datasetId, Path outputDir, Map<String, Object> verification) throws IOException {
        Files.createDirectories(outputDir);
        Path json = outputDir.resolve(datasetId + "-verification.json");
        Files.writeString(json, MAPPER.writeValueAsString(verification) + "\n", StandardCharsets.UTF_8);
        return json;
    }

    public static String answersCsv(Dar012Plan plan) {
        StringBuilder sb = new StringBuilder("contract_no,scenario,expected_result_type,expected_amount,note\n");
        for (AnswerRow a : plan.answers()) {
            sb.append(a.contractNo()).append(',').append(a.scenario()).append(',').append(a.expectedResultType()).append(',')
                    .append(a.expectedAmount() == null ? "" : a.expectedAmount().toPlainString()).append(',')
                    .append(csv(a.note())).append('\n');
        }
        return sb.toString();
    }

    private static String csv(String value) {
        return value.contains(",") || value.contains("\"") ? '"' + value.replace("\"", "\"\"") + '"' : value;
    }
}
