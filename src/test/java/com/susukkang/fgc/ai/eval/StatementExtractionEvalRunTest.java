package com.susukkang.fgc.ai.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.ai.eval.ExtractionEvaluator.ExtractedRow;
import com.susukkang.fgc.ai.eval.ExtractionEvaluator.Result;
import com.susukkang.fgc.ai.eval.ExtractionEvaluator.RunMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : 10/27 PoC 측정 실행기(#638) — FGC_EVAL_RUN 폴더의 before-review.json(추출 직후 내보내기)·after-review.json(검토 완료 뒤
 * 내보내기)·run.json(토큰·소요시간)을
 * 고정 정답과 비교해 같은 폴더에 result-{시각}.md·.json 을 남긴다. 실제 API 를 부르지 않는다. 절차는 resources/ai-eval/statement-01/README.md.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-10-23
 */
@EnabledIfEnvironmentVariable(named = "FGC_EVAL_RUN", matches = ".+")
class StatementExtractionEvalRunTest {

    @Test
    void evaluatesExportedExtraction() throws IOException {
        Path dir = Path.of(System.getenv("FGC_EVAL_RUN"));
        // source_location 등에 #640 이 더 담는 키(열 위치 등)는 평가에 쓰지 않는다
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        TypeReference<List<ExtractedRow>> rowsType = new TypeReference<>() {
        };
        List<ExtractedRow> rows = ExtractionEvaluator.withReviewEdits(
                mapper.readValue(dir.resolve("before-review.json").toFile(), rowsType),
                mapper.readValue(dir.resolve("after-review.json").toFile(), rowsType));
        RunMeta meta = mapper.readValue(dir.resolve("run.json").toFile(), RunMeta.class);

        Result result = ExtractionEvaluator.evaluate(StatementGoldSample.readCommittedGold(), rows, meta);
        Path markdown = ExtractionEvaluator.write(result, dir,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));

        System.out.println("TER-011 결과표: " + markdown.toAbsolutePath() + " — " + result.verdict());
        assertThat(markdown).exists();
    }
}
