package com.susukkang.fgc.ai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.susukkang.fgc.ai.eval.ExtractionEvaluator.ExtractedRow;
import com.susukkang.fgc.ai.eval.ExtractionEvaluator.Result;
import com.susukkang.fgc.ai.eval.ExtractionEvaluator.RunMeta;
import com.susukkang.fgc.ai.eval.ExtractionEvaluator.SourceLocation;
import com.susukkang.fgc.ai.eval.StatementGoldSample.Gold;
import com.susukkang.fgc.ai.eval.StatementGoldSample.GoldRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.susukkang.fgc.ai.eval.StatementGoldSample.FEE_SHEET;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : #638 테스트 시나리오 1·2·4·5·6 — 저장된 정답 JSON 으로만 돈다(실제 API 호출 없음, TER-011).
 *
 * @author yslee
 * @version 1.0
 * @since 2026-10-23
 */
class ExtractionEvaluatorTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final RunMeta AI_RUN = new RunMeta("gpt-4o-mini", 100_000, 20_000, 3, 0.15, 0.60, 900L);

    private final Gold gold = StatementGoldSample.readCommittedGold();

    /** 시나리오 1 — 결과표에 정확도·오수락·누락·합계·수정률·시간·비용이 모두 남는다. */
    @Test
    void perfectExtractionSavesFullResultTable(@TempDir Path dir) throws IOException {
        Result result = ExtractionEvaluator.evaluate(gold, extraction("READY"), AI_RUN);

        assertThat(result.overallFieldAccuracy()).startsWith("100.00%");
        assertThat(result.missingRows()).isZero();
        assertThat(result.extraRows()).isZero();
        assertThat(result.falseAcceptances()).isZero();
        assertThat(result.noTouchRate()).startsWith("100.00%");
        assertThat(result.sums()).allMatch(sum -> sum.diff().signum() == 0);
        assertThat(result.costUsd()).startsWith("0.027000"); // 100,000×0.15 + 20,000×0.60 = 27,000 / 1e6
        assertThat(result.path()).startsWith("AI 추출");

        Path markdown = ExtractionEvaluator.write(result, dir, "20261027-1400");
        assertThat(Files.readString(markdown)).contains("필드 정확도", "오수락", "누락 행", "시트별 합계", "무수정 처리율",
                "사람 수정률", "업무 소요시간 | 900초", "비용(USD)");
        assertThat(dir.resolve("result-20261027-1400.json")).exists();
    }

    /** 시나리오 2 — 같은 입력이면 같은 결과. */
    @Test
    void sameInputGivesSameResult() {
        assertThat(ExtractionEvaluator.evaluate(gold, extraction("READY"), AI_RUN))
                .isEqualTo(ExtractionEvaluator.evaluate(gold, extraction("READY"), AI_RUN));
    }

    /** 시나리오 4 — 틀린 금액을 정상(READY)으로 통과시키면 시연 차단. 같은 오류라도 '확인 필요'로 걸렀으면 오수락이 아니다. */
    @Test
    void wrongAmountPassedAsReadyIsBlockingDefect() {
        List<ExtractedRow> rows = extraction("READY");
        rows.set(10, withField(rows.get(10), "amount", JSON.numberNode(gold.rows().get(10).amount() + 1_000)));

        Result result = ExtractionEvaluator.evaluate(gold, rows, AI_RUN);
        assertThat(result.falseAcceptances()).isEqualTo(1);
        assertThat(result.verdict()).startsWith("시연 차단 결함 1건");
        assertThat(result.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.kind()).isEqualTo("오수락");
            assertThat(failure.field()).isEqualTo("amount");
        });

        rows.set(10, withStatus(rows.get(10), "REVIEW_REQUIRED"));
        Result flagged = ExtractionEvaluator.evaluate(gold, rows, AI_RUN);
        assertThat(flagged.falseAcceptances()).isZero();
        assertThat(flagged.verdict()).startsWith("차단 결함 0건");
        assertThat(flagged.failures()).singleElement().extracting(ExtractionEvaluator.Failure::kind).isEqualTo("필드 오류");
    }

    /** 선행 0 이 빠진 모집자 코드도 필수 식별값 오수락이다. */
    @Test
    void droppedLeadingZeroIsFalseAcceptance() {
        List<ExtractedRow> rows = extraction("READY");
        int index = indexOf(row -> row.agentCode().startsWith("0"));
        String code = gold.rows().get(index).agentCode();
        rows.set(index, withField(rows.get(index), "agentCode", JSON.numberNode(Long.parseLong(code))));

        assertThat(ExtractionEvaluator.evaluate(gold, rows, AI_RUN).falseAcceptances()).isEqualTo(1);
    }

    /** 시나리오 5 — 전부 '확인 필요'로 보내면 정확도는 100%여도 무수정 처리율 0%로 드러난다. */
    @Test
    void sendingEverythingToReviewShowsInNoTouchRate() {
        Result result = ExtractionEvaluator.evaluate(gold, extraction("REVIEW_REQUIRED"), AI_RUN);

        assertThat(result.overallFieldAccuracy()).startsWith("100.00%");
        assertThat(result.falseAcceptances()).isZero();
        assertThat(result.reviewRequiredRate()).startsWith("100.00%");
        assertThat(result.noTouchRate()).startsWith("0.00%");
    }

    /** 시나리오 6 — AI 성공 호출이 없으면 '대체 경로 동작'으로 따로 표시하고 AI 추출로 적지 않는다. */
    @Test
    void deterministicParserRunIsLabeledFallback() {
        RunMeta parserOnly = new RunMeta("gpt-4o-mini", 0, 0, 0, 0.15, 0.60, null);
        Result result = ExtractionEvaluator.evaluate(gold, extraction("READY"), parserOnly);

        assertThat(result.path()).startsWith("대체 경로 동작").doesNotContain("AI 추출(");
        assertThat(result.elapsed()).isEqualTo("미측정");
        assertThat(ExtractionEvaluator.markdown(result, "r1")).contains("경로: 대체 경로 동작");
    }

    /** SRC-057 '시트당 앞 1,000행'에서 잘린 행은 누락으로, 소계 행을 데이터로 읽어 통과시키면 오수락·합계 차이로 잡는다. */
    @Test
    void truncatedRowsAndSubtotalReadAsDataAreCaught() {
        List<ExtractedRow> rows = new ArrayList<>(extraction("READY").stream()
                .filter(row -> !(row.sourceLocation().sheet().equals(FEE_SHEET) && row.sourceLocation().row() > 1_000)).toList());
        int missing = gold.rows().size() - rows.size();
        rows.add(new ExtractedRow(new SourceLocation(FEE_SHEET, 53), "READY", false,
                Map.of("amount", JSON.numberNode(1_000_000), "cashflowType", JSON.textNode("PAYMENT"))));

        Result result = ExtractionEvaluator.evaluate(gold, rows, AI_RUN);
        assertThat(missing).isPositive();
        assertThat(result.missingRows()).isEqualTo(missing);
        assertThat(result.extraRows()).isEqualTo(1);
        assertThat(result.falseAcceptances()).isEqualTo(1);
        assertThat(result.sums().getFirst().diff()).isNotEqualByComparingTo(BigDecimal.ZERO);
    }

    /** 검토 중 소계 행을 '제외'해 row_status 가 EXCLUDED 로 바뀌어도, 시스템이 READY 로 통과시킨 오수락은 검토 전 상태로 남는다. */
    @Test
    void exclusionDuringReviewDoesNotHideFalseAcceptance() {
        List<ExtractedRow> before = extraction("READY");
        before.add(new ExtractedRow(new SourceLocation(FEE_SHEET, 53), "READY", false,
                Map.of("amount", JSON.numberNode(38_857_590), "cashflowType", JSON.textNode("PAYMENT"))));
        List<ExtractedRow> after = new ArrayList<>(before);
        after.set(after.size() - 1, withStatus(after.getLast(), "EXCLUDED"));
        after.set(0, new ExtractedRow(after.get(0).sourceLocation(), "READY", true, after.get(0).fields()));

        Result result = ExtractionEvaluator.evaluate(gold, ExtractionEvaluator.withReviewEdits(before, after), AI_RUN);
        assertThat(result.falseAcceptances()).isEqualTo(1);
        assertThat(result.failures()).singleElement().extracting(ExtractionEvaluator.Failure::kind).isEqualTo("오수락(없는 행)");
        assertThat(result.humanEditRate()).startsWith("0.09% (1/1062)");
        assertThat(ExtractionEvaluator.evaluate(gold, after, AI_RUN).falseAcceptances()).as("검토 뒤 상태만 보면 놓친다").isZero();
    }

    private List<ExtractedRow> extraction(String status) {
        List<ExtractedRow> rows = new ArrayList<>();
        for (GoldRow row : gold.rows()) {
            Map<String, JsonNode> fields = new LinkedHashMap<>();
            fields.put("settlementMonth", JSON.textNode(row.settlementMonth()));
            fields.put("policyNo", JSON.textNode(row.policyNo()));
            fields.put("agentCode", JSON.textNode(row.agentCode()));
            fields.put("productCode", JSON.textNode(row.productCode()));
            fields.put("installment", JSON.numberNode(row.installment()));
            fields.put("itemName", JSON.textNode(row.itemName()));
            fields.put("cashflowType", JSON.textNode(row.cashflowType()));
            fields.put("amount", JSON.numberNode(row.amount()));
            rows.add(new ExtractedRow(new SourceLocation(row.sheet(), row.row()), status, false, fields));
        }
        return rows;
    }

    private static ExtractedRow withField(ExtractedRow row, String field, JsonNode value) {
        Map<String, JsonNode> fields = new LinkedHashMap<>(row.fields());
        fields.put(field, value);
        return new ExtractedRow(row.sourceLocation(), row.rowStatus(), row.edited(), fields);
    }

    private static ExtractedRow withStatus(ExtractedRow row, String status) {
        return new ExtractedRow(row.sourceLocation(), status, row.edited(), row.fields());
    }

    private int indexOf(java.util.function.Predicate<GoldRow> predicate) {
        for (int i = 0; i < gold.rows().size(); i++) {
            if (predicate.test(gold.rows().get(i))) {
                return i;
            }
        }
        throw new IllegalStateException("no row");
    }
}
