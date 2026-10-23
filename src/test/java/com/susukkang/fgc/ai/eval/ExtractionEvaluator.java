package com.susukkang.fgc.ai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.susukkang.fgc.ai.eval.StatementGoldSample.Gold;
import com.susukkang.fgc.ai.eval.StatementGoldSample.GoldRow;
import com.susukkang.fgc.ai.eval.StatementGoldSample.SheetTotal;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 설명 : TER-011 정산데이터 추출 평가(#638) — 추출 초안 행(intake_draft_row 모양)을 정답과 엑셀 위치(시트·행)로 맞대어
 * 필드 정확도·오수락·누락/초과 행·합계 차이·확인 필요 비율·무수정 처리율·사람 수정률·소요시간·비용을 계산하고 결과표로 남긴다.
 * 합격 기준 수치는 없다 — 10/27 PoC 실측 뒤 정한다(TER-011). 오수락이 1건이라도 있으면 시연 차단 결함으로 표시한다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-10-23
 */
final class ExtractionEvaluator {

    static final List<String> FIELDS = List.of("settlementMonth", "policyNo", "agentCode", "productCode",
            "installment", "itemName", "cashflowType", "amount");
    /** 오수락을 세는 필수 식별값·금액(프로젝트 가정 — FUN-068 매칭·대사가 기대는 값). 상품·회차·항목은 매칭 단계가 '확인 필요'로 거른다. */
    static final Set<String> KEY_FIELDS = Set.of("settlementMonth", "policyNo", "agentCode", "cashflowType", "amount");
    private static final Set<String> NUMERIC_FIELDS = Set.of("installment", "amount");
    /** 시스템이 정상으로 통과시킨 상태. CONFIRMED 는 내보내기가 확정 뒤였을 때를 위해 보수적으로 같이 센다. */
    private static final Set<String> ACCEPTED = Set.of("READY", "CONFIRMED");
    private static final Set<String> REVIEW = Set.of("REVIEW_REQUIRED", "ERROR");
    private static final int MARKDOWN_FAILURE_LIMIT = 50;

    record SourceLocation(String sheet, int row) {
    }

    /** fields 는 사람 수정 전 시스템 값(normalized_fields), edited 는 사람이 고쳤는지(edited_fields 있음). */
    record ExtractedRow(SourceLocation sourceLocation, String rowStatus, boolean edited, Map<String, JsonNode> fields) {
    }

    /**
     * aiSuccessCalls 는 이 문서의 ai_call_log 중 purpose=INTAKE_EXTRACT·SUCCESS 건수 — 서식 분류(INTAKE_CLASSIFY)는 세지 않는다.
     * 0 이면 행 추출은 결정론 파서만 돈 '대체 경로'로 본다. 토큰은 비용이므로 분류 호출까지 모두 더한다.
     */
    record RunMeta(String model, long inputTokens, long outputTokens, int aiSuccessCalls,
                   double inputUsdPerMTok, double outputUsdPerMTok, Long elapsedSeconds) {
    }

    record Failure(String kind, String sheet, int row, String field, String expected, String actual, String rowStatus) {
    }

    record SheetSum(String sheet, BigDecimal goldNet, BigDecimal extractedNet, BigDecimal diff) {
    }

    record Result(String datasetId, String path, String verdict, int goldRows, int extractedRows, int missingRows,
                  int extraRows, Map<String, String> fieldAccuracy, String overallFieldAccuracy, int falseAcceptances,
                  String reviewRequiredRate, String noTouchRate, String humanEditRate, String elapsed, String costUsd,
                  List<SheetSum> sums, List<Failure> failures) {
    }

    private ExtractionEvaluator() {
    }

    static Result evaluate(Gold gold, List<ExtractedRow> extracted, RunMeta meta) {
        Map<SourceLocation, GoldRow> goldByLocation = gold.rows().stream().collect(Collectors.toMap(
                row -> new SourceLocation(row.sheet(), row.row()), Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<String, Integer> correct = new LinkedHashMap<>();
        FIELDS.forEach(field -> correct.put(field, 0));
        Map<String, BigDecimal> extractedNet = new LinkedHashMap<>();
        List<Failure> failures = new ArrayList<>();
        Set<SourceLocation> seen = new HashSet<>();
        int falseAcceptances = 0;
        int extraRows = 0;
        int noTouch = 0;

        for (ExtractedRow row : extracted) {
            SourceLocation location = row.sourceLocation();
            String status = row.rowStatus();
            boolean accepted = ACCEPTED.contains(status);
            if (!"EXCLUDED".equals(status)) {
                extractedNet.merge(location.sheet(), signedAmount(row), BigDecimal::add);
            }
            GoldRow expected = seen.add(location) ? goldByLocation.get(location) : null;
            if (expected == null) {
                extraRows++;
                failures.add(new Failure(accepted ? "오수락(없는 행)" : "초과 행", location.sheet(), location.row(),
                        null, null, actual(row, "amount"), status));
                if (accepted) {
                    falseAcceptances++;
                }
                continue;
            }
            boolean allCorrect = true;
            boolean keyWrong = false;
            for (String field : FIELDS) {
                String want = expected(expected, field);
                String got = actual(row, field);
                if (want.equals(got)) {
                    correct.merge(field, 1, Integer::sum);
                } else {
                    allCorrect = false;
                    keyWrong |= KEY_FIELDS.contains(field);
                    failures.add(new Failure(accepted && KEY_FIELDS.contains(field) ? "오수락" : "필드 오류",
                            location.sheet(), location.row(), field, want, got, status));
                }
            }
            if (accepted && keyWrong) {
                falseAcceptances++;
            }
            if (accepted && allCorrect && !row.edited()) {
                noTouch++;
            }
        }

        int missing = 0;
        for (GoldRow row : gold.rows()) {
            if (!seen.contains(new SourceLocation(row.sheet(), row.row()))) {
                missing++;
                failures.add(new Failure("누락 행", row.sheet(), row.row(), null, Long.toString(row.amount()), null, null));
            }
        }

        int goldRows = gold.rows().size();
        Map<String, String> fieldAccuracy = new LinkedHashMap<>();
        correct.forEach((field, count) -> fieldAccuracy.put(field, percent(count, goldRows)));
        int allFieldsCorrect = correct.values().stream().mapToInt(Integer::intValue).sum();
        List<SheetSum> sums = new ArrayList<>();
        for (SheetTotal total : gold.totals()) {
            BigDecimal goldNet = BigDecimal.valueOf(total.netWon());
            BigDecimal got = extractedNet.getOrDefault(total.sheet(), BigDecimal.ZERO);
            sums.add(new SheetSum(total.sheet(), goldNet, got, got.subtract(goldNet)));
        }
        long reviewRows = extracted.stream().filter(row -> REVIEW.contains(row.rowStatus())).count();
        long editedRows = extracted.stream().filter(ExtractedRow::edited).count();
        boolean ai = meta.aiSuccessCalls() > 0;
        String path = ai ? "AI 추출(추출 호출 성공 " + meta.aiSuccessCalls() + "회)"
                : "대체 경로 동작(결정론 파서 — AI 추출 합격으로 보지 않음)";
        String verdict = falseAcceptances > 0
                ? "시연 차단 결함 " + falseAcceptances + "건(오수락)"
                : "차단 결함 0건 — 합격 기준 미정(10/27 PoC 실측 뒤 TER-011 검증기준에 기록)";
        BigDecimal cost = BigDecimal.valueOf(meta.inputTokens() * meta.inputUsdPerMTok() + meta.outputTokens() * meta.outputUsdPerMTok())
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);

        return new Result(gold.meta().datasetId(), path, verdict, goldRows, extracted.size(), missing, extraRows,
                fieldAccuracy, percent(allFieldsCorrect, (long) goldRows * FIELDS.size()), falseAcceptances,
                percent(reviewRows, extracted.size()), percent(noTouch, goldRows), percent(editedRows, extracted.size()),
                meta.elapsedSeconds() == null ? "미측정" : meta.elapsedSeconds() + "초",
                cost.toPlainString() + " (" + meta.model() + ", 입력 " + meta.inputTokens() + "·출력 " + meta.outputTokens() + " 토큰)",
                sums, failures);
    }

    /**
     * 검토 전 내보내기(시스템 판단 그대로)에 검토 뒤 내보내기의 사람 수정 여부만 얹는다.
     * 검토 중 '제외'는 row_status 를 EXCLUDED 로 덮어쓰므로(V58 ck_intake_draft_row_excluded) 상태는 검토 전 것을 쓴다.
     */
    static List<ExtractedRow> withReviewEdits(List<ExtractedRow> beforeReview, List<ExtractedRow> afterReview) {
        Set<SourceLocation> edited = afterReview.stream().filter(ExtractedRow::edited)
                .map(ExtractedRow::sourceLocation).collect(Collectors.toSet());
        return beforeReview.stream().map(row -> new ExtractedRow(row.sourceLocation(), row.rowStatus(),
                edited.contains(row.sourceLocation()), row.fields())).toList();
    }

    /** 실행마다 result-{runId}.md·.json 을 남긴다. */
    static Path write(Result result, Path dir, String runId) throws IOException {
        Files.createDirectories(dir);
        Path markdown = dir.resolve("result-" + runId + ".md");
        Files.writeString(markdown, markdown(result, runId), StandardCharsets.UTF_8);
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(dir.resolve("result-" + runId + ".json").toFile(), result);
        return markdown;
    }

    static String markdown(Result result, String runId) {
        StringBuilder md = new StringBuilder();
        md.append("# TER-011 정산데이터 추출 평가 — ").append(result.datasetId()).append("\n\n")
                .append("- 실행: ").append(runId).append("\n")
                .append("- 경로: ").append(result.path()).append("\n")
                .append("- 판정: **").append(result.verdict()).append("**\n\n")
                .append("| 항목 | 값 |\n|---|---|\n")
                .append("| 정답 행 / 추출 행 | ").append(result.goldRows()).append(" / ").append(result.extractedRows()).append(" |\n")
                .append("| 누락 행 / 초과 행 | ").append(result.missingRows()).append(" / ").append(result.extraRows()).append(" |\n")
                .append("| 필드 정확도(전체) | ").append(result.overallFieldAccuracy()).append(" |\n")
                .append("| 오수락(필수 식별값·금액) | ").append(result.falseAcceptances()).append("건 |\n")
                .append("| 확인 필요 비율 | ").append(result.reviewRequiredRate()).append(" |\n")
                .append("| 무수정 처리율 | ").append(result.noTouchRate()).append(" |\n")
                .append("| 사람 수정률 | ").append(result.humanEditRate()).append(" |\n")
                .append("| 업무 소요시간 | ").append(result.elapsed()).append(" |\n")
                .append("| 비용(USD) | ").append(result.costUsd()).append(" |\n\n")
                .append("## 필드별 정확도\n\n| 필드 | 정확도 |\n|---|---|\n");
        result.fieldAccuracy().forEach((field, value) -> md.append("| ").append(field).append(" | ").append(value).append(" |\n"));
        md.append("\n## 시트별 합계(원, 지급 − 차감)\n\n| 시트 | 원본 합계 | 추출 합계 | 차이 |\n|---|---|---|---|\n");
        result.sums().forEach(sum -> md.append("| ").append(sum.sheet()).append(" | ").append(sum.goldNet().toPlainString())
                .append(" | ").append(sum.extractedNet().toPlainString()).append(" | ").append(sum.diff().toPlainString()).append(" |\n"));
        md.append("\n## 실패 목록 (").append(result.failures().size()).append("건");
        if (result.failures().size() > MARKDOWN_FAILURE_LIMIT) {
            md.append(", 앞 ").append(MARKDOWN_FAILURE_LIMIT).append("건 — 전체는 result-").append(runId).append(".json");
        }
        md.append(")\n\n| 구분 | 시트 | 행 | 필드 | 정답 | 추출 | 행 상태 |\n|---|---|---|---|---|---|---|\n");
        result.failures().stream().limit(MARKDOWN_FAILURE_LIMIT).forEach(failure -> md.append("| ")
                .append(String.join(" | ", failure.kind(), failure.sheet(), Integer.toString(failure.row()),
                        dash(failure.field()), dash(failure.expected()), dash(failure.actual()), dash(failure.rowStatus())))
                .append(" |\n"));
        return md.toString();
    }

    private static String expected(GoldRow row, String field) {
        return switch (field) {
            case "settlementMonth" -> row.settlementMonth();
            case "policyNo" -> row.policyNo();
            case "agentCode" -> row.agentCode();
            case "productCode" -> row.productCode();
            case "installment" -> Integer.toString(row.installment());
            case "itemName" -> row.itemName();
            case "cashflowType" -> row.cashflowType();
            case "amount" -> Long.toString(row.amount());
            default -> throw new IllegalArgumentException(field);
        };
    }

    /** 코드는 글자 그대로 비교한다 — 선행 0 이 빠진 "417" 은 "000417" 과 다르다. 숫자 필드만 숫자로 맞춘다. */
    private static String actual(ExtractedRow row, String field) {
        JsonNode value = row.fields() == null ? null : row.fields().get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.isNumber() ? value.decimalValue().toPlainString() : value.asText().trim();
        if (NUMERIC_FIELDS.contains(field)) {
            try {
                return new BigDecimal(text).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException e) {
                return text;
            }
        }
        return text;
    }

    private static BigDecimal signedAmount(ExtractedRow row) {
        String amount = actual(row, "amount");
        try {
            BigDecimal value = amount == null ? BigDecimal.ZERO : new BigDecimal(amount);
            return "DEDUCTION".equals(actual(row, "cashflowType")) ? value.negate() : value;
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static String percent(long count, long total) {
        return total == 0 ? "-" : String.format("%.2f%% (%d/%d)", count * 100.0 / total, count, total);
    }

    private static String dash(String value) {
        return value == null ? "-" : value;
    }
}
