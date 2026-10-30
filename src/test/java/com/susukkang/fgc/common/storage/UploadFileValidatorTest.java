package com.susukkang.fgc.common.storage;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

class UploadFileValidatorTest {
    private final UploadFileValidator validator = new UploadFileValidator(new FileStorageProperties(Path.of("files"), 1024 * 1024));

    @Test void validatedValueUsesArrayContentWithoutAllowingMutationOrContentLogging() {
        byte[] input = "DO_NOT_LOG".getBytes(StandardCharsets.UTF_8);
        var first = new UploadFileValidator.ValidatedFile("secret.csv", "text/csv", input, "hash");
        var same = new UploadFileValidator.ValidatedFile("secret.csv", "text/csv", input.clone(), "hash");
        assertThat(first).isEqualTo(same).hasSameHashCodeAs(same);
        input[0] = 0;
        byte[] returned = first.bytes();
        returned[0] = 0;
        assertThat(first).isEqualTo(same);
        assertThat(first.bytes()).isEqualTo("DO_NOT_LOG".getBytes(StandardCharsets.UTF_8));
        assertThat(first.toString()).doesNotContain("secret.csv", "DO_NOT_LOG");
    }

    @Test void acceptsXlsxAndHashesExactBytes() throws Exception {
        byte[] bytes = workbook(false, false);
        var result = validator.validate(file("원본.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes));
        assertThat(result.sha256()).hasSize(64);
        assertThat(result.bytes()).isEqualTo(bytes);
    }

    @Test void acceptsRealLegacyExcel() throws Exception {
        assertThat(validator.validate(file("sample.xls", "application/vnd.ms-excel", workbook(true, false))).mediaType())
                .isEqualTo("application/vnd.ms-excel");
    }

    @Test void acceptsPdf() throws Exception {
        assertThat(validator.validate(file("sample.pdf", "application/pdf", pdf(false))).bytes()).isNotEmpty();
    }

    @Test void rejectsPdfJavaScript() throws Exception { rejects(file("sample.pdf", "application/pdf", pdf(true))); }

    @ParameterizedTest @ValueSource(strings = {"png", "jpg"})
    void acceptsRealImages(String format) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), format, out);
        assertThat(validator.validate(file("sample." + format, format.equals("png") ? "image/png" : "image/jpeg", out.toByteArray())))
                .isNotNull();
    }

    @Test void acceptsUtf8CsvAndNegativeAmount() {
        assertThat(validator.validate(file("sample.csv", "text/csv; charset=UTF-8", "증권,금액\n001,-1000".getBytes(StandardCharsets.UTF_8))))
                .isNotNull();
    }

    @Test void acceptsQuotedCsvAndWindowsExcelMimeAlias() {
        assertThat(validator.validate(file("sample.csv", "application/vnd.ms-excel",
                "name,amount\r\n\"정상,문서;@부서\",\"-1,000\"".getBytes(StandardCharsets.UTF_8))).mediaType()).isEqualTo("text/csv");
    }

    @Test void rejectsMalformedCsvQuotes() {
        rejects(file("sample.csv", "text/csv", "a,\"unfinished".getBytes(StandardCharsets.UTF_8)));
    }

    @Test void acceptsExistingStatementGoldSampleWithoutChangingIt() throws Exception {
        try (var stream = getClass().getResourceAsStream("/ai-eval/statement-01/statement-01.xlsx")) {
            assertThat(stream).isNotNull();
            assertThat(validator.validate(file("statement.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    stream.readAllBytes()))).isNotNull();
        }
    }

    @Test void rejectsWorkbookExternalRelationships() throws Exception {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var cell = workbook.createSheet("data").createRow(0).createCell(0);
            cell.setCellValue("external");
            var link = workbook.getCreationHelper().createHyperlink(org.apache.poi.common.usermodel.HyperlinkType.URL);
            link.setAddress("https://example.invalid/");
            cell.setHyperlink(link);
            workbook.write(out);
            rejects(file("sample.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray()));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"../../sample.pdf", "C:\\sample.pdf", "file.exe", "sample.csv\n\n.csv", "file.csv ", ".csv"})
    void rejectsNames(String name) { rejects(file(name, "text/csv", "a,b".getBytes(StandardCharsets.UTF_8))); }

    @Test void rejectsMimeMismatch() { rejects(file("sample.csv", "application/pdf", new byte[]{1})); }
    @Test void rejectsForgedPdf() { rejects(file("sample.pdf", "application/pdf", "%PDF-executable".getBytes(StandardCharsets.UTF_8))); }
    @Test void rejectsForgedImage() { rejects(file("sample.png", "image/png", new byte[]{1})); }
    @Test void rejectsOversize() { rejects(file("sample.csv", "text/csv", new byte[1024 * 1024 + 1])); }
    @Test void rejectsEmpty() { rejects(file("sample.csv", "text/csv", new byte[0])); }
    @Test void rejectsMalformedUtf8() { rejects(file("sample.csv", "text/csv", new byte[]{(byte) 0xff})); }
    @Test void rejectsSpreadsheetFormulas() throws Exception {
        rejects(file("sample.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook(false, true)));
    }

    @ParameterizedTest @ValueSource(strings = {"a,=HYPERLINK(1)", "a,\"  @SUM(1)\"", "a,+cmd", "a,-cmd", "\uFEFF=cmd,value"})
    void rejectsCsvFormulaInjection(String content) { rejects(file("sample.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8))); }

    @Test void rejectsZipBomb() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(out)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("xl/workbook.xml"));
            byte[] block = new byte[1024 * 1024];
            for (int i = 0; i < 65; i++) zip.write(block);
        }
        rejects(file("sample.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray()));
    }

    private void rejects(MockMultipartFile file) {
        assertThatThrownBy(() -> validator.validate(file)).isInstanceOfSatisfying(FgcBusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(FgcErrorCode.INTK_001));
    }

    static MockMultipartFile file(String name, String media, byte[] bytes) { return new MockMultipartFile("file", name, media, bytes); }
    static byte[] workbook(boolean legacy, boolean formula) throws Exception {
        try (var workbook = legacy ? new HSSFWorkbook() : new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var cell = workbook.createSheet("명세").createRow(0).createCell(0);
            if (formula) cell.setCellFormula("1+1"); else cell.setCellValue("합성 문서");
            workbook.write(out);
            return out.toByteArray();
        }
    }
    private static byte[] pdf(boolean script) throws Exception {
        try (var document = new PDDocument(); var out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            if (script) document.getDocumentCatalog().setOpenAction(new PDActionJavaScript("app.alert('test')"));
            document.save(out);
            return out.toByteArray();
        }
    }
}
