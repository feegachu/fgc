package com.susukkang.fgc.clawback.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;
import com.susukkang.fgc.clawback.service.ClawbackNoticePdfRenderer.*;

class ClawbackNoticePdfRendererTest {
    private Notice sample(List<Line> lines) {
        return new Notice("FGC-CLAW-202611-001", 2, OffsetDateTime.parse("2026-11-08T09:00:00+09:00"),
                "FGC-FGL01-202608-0001", "미래가상생명", "가상 건강보장보험 A", "FC-001", "김정산",
                "차익거래 검토", LocalDate.of(2026, 10, 31), LocalDate.of(2026, 7, 1),
                "CLAW-GA-VIRTUAL-2026 v1", 3, OffsetDateTime.parse("2026-11-07T16:00:00+09:00"),
                3, "원 단위 버림", lines, lines.stream().map(Line::clawbackAmount).reduce(BigDecimal.ZERO, BigDecimal::add),
                List.of(new RateBand(1L, "초년도 수수료", 0, 3, new BigDecimal("80"), true),
                        new RateBand(2L, "초년도 수수료", 4, 6, new BigDecimal("60"), false),
                        new RateBand(3L, "유지 수수료", 0, 6, new BigDecimal("50"), true)),
                "계약 및 지급 원천 내역을 확인하고 계산한 검토용 명세",new Party("FGC 가상 GA","강동지사","강동1팀"));
    }
    @Test
    void producesThreeReadableKoreanPagesWithSnapshotAmountsAndAppliedRateBands() throws Exception {
        byte[] bytes = new ClawbackNoticePdfRenderer().render(sample(List.of(
                new Line("초년도 수수료", 1, new BigDecimal("840000"), new BigDecimal("80"), new BigDecimal("672000")),
                new Line("유지 수수료", 2, new BigDecimal("55000"), new BigDecimal("50"), new BigDecimal("27500")))));
        try (var pdf = Loader.loadPDF(bytes)) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(3);
            String text = new PDFTextStripper().getText(pdf);
            assertThat(pdf.getPage(0).getMediaBox().getWidth()).isLessThan(pdf.getPage(0).getMediaBox().getHeight());
            assertThat(pdf.getPage(1).getMediaBox().getWidth()).isGreaterThan(pdf.getPage(1).getMediaBox().getHeight());
            assertThat(pdf.getPage(2).getMediaBox().getWidth()).isGreaterThan(pdf.getPage(2).getMediaBox().getHeight());
            assertThat(text).contains("수수료 환수 안내 및", "계약별 환수 상세 명세", "상품·회차별 환수율 및 계산 근거", "699,500", "840,000", "80%", "60", "13회", "산정 기준일", "(17)", "금 육십구만구천오백원정");
            assertThat(text).doesNotContain("�");
        }
        Path output = Path.of("output/pdf/clawback-restored.pdf");
        Files.createDirectories(output.getParent());
        Files.write(output, bytes);
    }
    @Test
    void paginatesLongDetailsWithoutDroppingRows() throws Exception {
        var lines = IntStream.rangeClosed(1, 65).mapToObj(i -> new Line("긴 명세 항목 " + i, i,
                new BigDecimal("1000"), new BigDecimal("50"), new BigDecimal("500"))).toList();
        try (var pdf = Loader.loadPDF(new ClawbackNoticePdfRenderer().render(sample(lines)))) {
            assertThat(pdf.getNumberOfPages()).isGreaterThan(3);
            assertThat(new PDFTextStripper().getText(pdf)).contains("긴 명세 항목 65", "32,500");
        }
    }
}
