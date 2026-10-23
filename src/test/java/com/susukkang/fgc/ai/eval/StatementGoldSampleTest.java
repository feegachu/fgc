package com.susukkang.fgc.ai.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.ai.eval.StatementGoldSample.Generated;
import com.susukkang.fgc.ai.eval.StatementGoldSample.GoldRow;
import com.susukkang.fgc.ai.eval.StatementGoldSample.Sheet;
import com.susukkang.fgc.ai.eval.StatementGoldSample.SheetTotal;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.susukkang.fgc.ai.eval.StatementGoldSample.FEE_SHEET;
import static com.susukkang.fgc.ai.eval.StatementGoldSample.RESOURCE_DIR;
import static com.susukkang.fgc.ai.eval.StatementGoldSample.THOUSAND_SHEET;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : #638 고정 정답셋이 생성기와 같고(같은 seed → 같은 파일), TER-011 이 요구한 함정을 모두 담았는지 본다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-10-23
 */
class StatementGoldSampleTest {

    private final Generated generated = StatementGoldSample.generate();

    /** 시나리오 2 — 고정 파일을 손으로 고치거나 생성기만 바꾸면 여기서 깨진다(정답 회귀 기준). */
    @Test
    void committedFilesEqualGeneratorOutput() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        assertThat(mapper.readTree(RESOURCE_DIR.resolve(StatementGoldSample.GOLD_FILE).toFile()))
                .isEqualTo(mapper.readTree(StatementGoldSample.goldJson(generated.gold())));
        assertThat(StatementGoldSample.readCommittedGold()).isEqualTo(generated.gold());

        Map<String, byte[]> expected = StatementGoldSample.xlsxEntries(generated.sheets());
        try (InputStream in = Files.newInputStream(RESOURCE_DIR.resolve(StatementGoldSample.XLSX_FILE))) {
            Map<String, byte[]> committed = StatementGoldSample.unzip(in);
            assertThat(committed.keySet()).containsExactlyElementsOf(expected.keySet());
            expected.forEach((name, bytes) -> assertThat(committed.get(name)).as(name).isEqualTo(bytes));
        }
    }

    @Test
    void sampleContainsTer011Traps() {
        List<GoldRow> rows = generated.gold().rows();
        Sheet fee = sheet(FEE_SHEET);
        Sheet thousand = sheet(THOUSAND_SHEET);

        assertThat(generated.sheets()).hasSize(3);
        assertThat(rows.stream().filter(row -> row.sheet().equals(FEE_SHEET))).hasSize(1_001);
        assertThat(fee.rows().size()).as("1,000행 넘는 시트").isGreaterThan(1_000);
        assertThat(fee.rows().stream().filter(row -> "마감년월".equals(row.get(0)))).as("반복 헤더").hasSizeGreaterThan(1);
        assertThat(fee.rows().stream().filter(row -> "소계".equals(row.get(0)))).hasSizeGreaterThan(1);
        assertThat(rows).anyMatch(row -> row.cashflowType().equals("DEDUCTION") && row.sheet().equals(FEE_SHEET));
        assertThat(fee.rows()).anyMatch(row -> row.size() == 8 && row.get(7) instanceof Long amount && amount < 0);
        assertThat(thousand.rows().get(0).get(0).toString()).contains("천원");
        assertThat(thousand.rows()).anyMatch(row -> row.size() == 7 && row.get(6) instanceof BigDecimal value && value.scale() > 0);
        assertThat(rows).anyMatch(row -> row.agentCode().startsWith("0"));
        assertThat(rows).anyMatch(row -> row.productCode().startsWith("0"));
        assertThat(rows).allMatch(row -> row.amount() > 0);
    }

    /** 원본 합계표 = 정답 행 합 = 파일 마지막 '합계' 행(천원 시트는 ×1,000). */
    @Test
    void totalsMatchGoldRowsAndFileTotalRow() {
        for (SheetTotal total : generated.gold().totals()) {
            List<GoldRow> inSheet = generated.gold().rows().stream().filter(row -> row.sheet().equals(total.sheet())).toList();
            long net = inSheet.stream().mapToLong(row -> row.cashflowType().equals("PAYMENT") ? row.amount() : -row.amount()).sum();
            assertThat(total.dataRows()).isEqualTo(inSheet.size());
            assertThat(total.netWon()).isEqualTo(net).isEqualTo(total.paymentWon() - total.deductionWon());

            List<Object> last = sheet(total.sheet()).rows().getLast();
            Object fileTotal = last.stream().filter(Objects::nonNull).reduce((a, b) -> b).orElseThrow();
            BigDecimal won = new BigDecimal(fileTotal.toString()).movePointRight(total.unit().equals("천원") ? 3 : 0);
            assertThat(last.get(0)).isEqualTo("합계");
            assertThat(won).isEqualByComparingTo(BigDecimal.valueOf(net));
        }
    }

    private Sheet sheet(String name) {
        return generated.sheets().stream().filter(sheet -> sheet.name().equals(name)).findFirst().orElseThrow();
    }
}
