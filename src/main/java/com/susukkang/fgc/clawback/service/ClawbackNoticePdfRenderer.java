package com.susukkang.fgc.clawback.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * FUN-084 환수 통지서 PDF. 표시할 값은 호출자가 계산판 snapshot에서 넘기며 여기서 금액을 다시 계산하지 않는다.
 * 계약자 개인정보는 입력에 없다. 안내 문구는 가상 양식임을 밝히는 고정 문구만 쓴다.
 */
@Component
public class ClawbackNoticePdfRenderer {
    private static final String FONT = "fonts/NanumGothic-Regular.ttf";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final float MARGIN = 50;
    private static final float LINE = 16;
    /** 항목 표의 열 시작 x. 항목명·회차·기지급 기준액·환수율·환수액. */
    private static final float[] COLUMNS = {MARGIN, 250, 300, 400, 470};

    private final byte[] fontBytes;

    public ClawbackNoticePdfRenderer() {
        try (InputStream in = new ClassPathResource(FONT).getInputStream()) {
            this.fontBytes = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("통지서 한글 글꼴을 읽을 수 없습니다: " + FONT, e);
        }
    }

    public record Notice(String documentNo, int version, OffsetDateTime issuedAt, String contractNo,
                         String insurerName, String productName, String agentCode, String agentName,
                         String triggerLabel, LocalDate eventDate, LocalDate contractDate,
                         String policyLabel, int calculationVersion, OffsetDateTime calculatedAt,
                         Integer elapsedMonths, String rounding, List<Line> lines, BigDecimal total) { }

    public record Line(String itemName, Integer installmentNo, BigDecimal basisAmount, BigDecimal appliedRatePct,
                       BigDecimal clawbackAmount) { }

    public byte[] render(Notice notice) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType0Font font = PDType0Font.load(document, new ByteArrayInputStream(fontBytes), true);
            document.getDocumentInformation().setTitle(notice.documentNo() + " v" + notice.version());
            try (Writer writer = new Writer(document, font)) {
                writer.text(18, MARGIN, "환수 통지서 (가상 양식)");
                writer.gap();
                writer.field("문서번호", notice.documentNo() + "  /  " + notice.version() + "판");
                writer.field("발행일시", dateTime(notice.issuedAt()));
                writer.gap();
                writer.field("계약번호", notice.contractNo());
                writer.field("보험회사", notice.insurerName());
                writer.field("상품", notice.productName());
                writer.field("설계사", notice.agentName() + " (" + notice.agentCode() + ")");
                writer.field("계약일", String.valueOf(notice.contractDate()));
                writer.field("환수 원인", notice.triggerLabel());
                writer.field("원인 발생일", String.valueOf(notice.eventDate()));
                writer.gap();
                writer.field("적용 정책", notice.policyLabel());
                writer.field("계산판", notice.calculationVersion() + "판  /  계산일시 " + dateTime(notice.calculatedAt()));
                writer.field("경과월", notice.elapsedMonths() == null ? "-" : notice.elapsedMonths() + "개월");
                writer.field("반올림", notice.rounding());
                writer.gap();
                writer.row(true, "수수료 항목", "회차", "기지급 기준액", "환수율", "환수액");
                for (Line line : notice.lines()) {
                    writer.row(false, line.itemName(), line.installmentNo() == null ? "-" : line.installmentNo().toString(),
                            won(line.basisAmount()), rate(line.appliedRatePct()), won(line.clawbackAmount()));
                }
                writer.row(true, "합계", "", "", "", won(notice.total()));
                writer.gap();
                writer.text(9, MARGIN, "이 문서는 FGC가 계산판 기준으로 생성한 내부 검토용 가상 양식입니다.");
                writer.text(9, MARGIN, "실제 통지·이의·확정·회수를 의미하지 않으며, 환수율과 산식은 회사별 위촉계약 정책을 따릅니다.");
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("환수 통지서 PDF를 만들 수 없습니다.", e);
        }
    }

    private static String dateTime(OffsetDateTime value) {
        return value.atZoneSameInstant(SEOUL).format(DATE_TIME);
    }

    /** 계산판 금액은 이미 원 단위다. 소수가 남아 있으면 계산판이 잘못된 것이므로 반올림으로 가리지 않는다. */
    private static String won(BigDecimal amount) {
        DecimalFormat format = new DecimalFormat("#,##0");
        format.setRoundingMode(RoundingMode.UNNECESSARY);
        return format.format(amount) + "원";
    }

    private static String rate(BigDecimal ratePct) {
        return ratePct.stripTrailingZeros().toPlainString() + "%";
    }

    /** 위에서 아래로 줄을 쌓고 아래 여백에 닿으면 새 쪽을 연다. */
    private static final class Writer implements AutoCloseable {
        private final PDDocument document;
        private final PDType0Font font;
        private PDPageContentStream stream;
        private float y;

        Writer(PDDocument document, PDType0Font font) throws IOException {
            this.document = document;
            this.font = font;
            newPage();
        }

        private void newPage() throws IOException {
            if (stream != null) stream.close();
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = page.getMediaBox().getHeight() - MARGIN;
        }

        private void ensureRoom() throws IOException {
            if (y < MARGIN + LINE) newPage();
        }

        void gap() {
            y -= LINE / 2;
        }

        void text(float size, float x, String value) throws IOException {
            ensureRoom();
            draw(size, x, y, value);
            y -= size > 12 ? LINE * 1.5f : LINE;
        }

        void field(String label, String value) throws IOException {
            ensureRoom();
            draw(10, MARGIN, y, label);
            draw(10, MARGIN + 90, y, value);
            y -= LINE;
        }

        void row(boolean emphasis, String... cells) throws IOException {
            ensureRoom();
            for (int i = 0; i < cells.length; i++) draw(10, COLUMNS[i], y, cells[i]);
            if (emphasis) {
                stream.moveTo(MARGIN, y - 4);
                stream.lineTo(PDRectangle.A4.getWidth() - MARGIN, y - 4);
                stream.stroke();
            }
            y -= LINE;
        }

        private void draw(float size, float x, float baseline, String value) throws IOException {
            stream.beginText();
            stream.setFont(font, size);
            stream.newLineAtOffset(x, baseline);
            stream.showText(printable(value));
            stream.endText();
        }

        /** 글꼴에 없는 문자 하나 때문에 발행 전체가 실패하지 않게 '?'로 바꾼다. */
        private String printable(String value) {
            if (value == null) return "-";
            StringBuilder out = new StringBuilder(value.length());
            value.codePoints().forEach(cp -> {
                String ch = Character.toString(cp);
                try {
                    font.encode(ch);
                    out.append(ch);
                } catch (IOException | IllegalArgumentException e) {
                    out.append('?');
                }
            });
            return out.toString();
        }

        @Override
        public void close() throws IOException {
            stream.close();
        }
    }
}
