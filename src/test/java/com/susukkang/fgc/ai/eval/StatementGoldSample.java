package com.susukkang.fgc.ai.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 설명 : TER-011 최소 정답셋(#638) — 보험사 정산데이터 1종의 합성 xlsx 와 필드 단위 정답·원본 합계표를 고정 seed 로 만든다.
 * 1,001행(한 시트, SRC-057 '시트당 앞 1,000행' 경계)·여러 시트·반복 헤더·소계/합계행·음수 차감·원/천원·선행 0 코드를 담는다.
 * 계약자 개인정보는 만들지 않는다(SRC-027 D-05). 정답 행은 코드로만 넣는다 — LLM 이 정답을 쓰지 않는다.
 * 고정 파일은 src/test/resources/ai-eval/statement-01 에 있고, 바꾸려면 GENERATOR_VERSION 을 올린 뒤 main 으로 다시 쓴다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-10-23
 */
final class StatementGoldSample {

    static final String DATASET_ID = "TER011-STMT-01";
    static final String GENERATOR_VERSION = "1";
    static final long SEED = 638L;
    static final Path RESOURCE_DIR = Path.of("src/test/resources/ai-eval/statement-01");
    static final String XLSX_FILE = "statement-01.xlsx";
    static final String GOLD_FILE = "statement-01.gold.json";

    static final String FEE_SHEET = "수수료명세";
    static final String THOUSAND_SHEET = "환수차감(천원)";
    static final String NOTE_SHEET = "안내";
    static final int FEE_DATA_ROWS = 1_001;
    static final int THOUSAND_DATA_ROWS = 60;
    private static final int BLOCK_ROWS = 50;
    private static final String MONTH = "2026-09";
    private static final List<String> PRODUCTS = List.of("0101", "0102", "0032", "0045", "1001", "0007", "0210", "0008");
    private static final List<String> PAYMENT_ITEMS = List.of("초회수수료", "유지수수료", "시책");
    private static final List<String> DEDUCTION_ITEMS = List.of("환수", "차감");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    record GoldRow(String sheet, int row, String settlementMonth, String policyNo, String agentCode,
                   String productCode, int installment, String itemName, String cashflowType, long amount) {
    }

    record SheetTotal(String sheet, String unit, int dataRows, long paymentWon, long deductionWon, long netWon) {
    }

    record Meta(String datasetId, String generatorVersion, long seed, String insurer, String settlementMonth,
                String xlsxFile, String note) {
    }

    record Gold(Meta meta, List<SheetTotal> totals, List<GoldRow> rows) {
    }

    /** 셀 값은 String(텍스트 셀)·Number(숫자 셀)·null(빈 셀) */
    record Sheet(String name, List<List<Object>> rows) {
    }

    record Generated(Gold gold, List<Sheet> sheets) {
    }

    private StatementGoldSample() {
    }

    public static void main(String[] args) throws IOException {
        Generated generated = generate();
        Files.createDirectories(RESOURCE_DIR);
        Files.write(RESOURCE_DIR.resolve(XLSX_FILE), zip(xlsxEntries(generated.sheets())));
        Files.writeString(RESOURCE_DIR.resolve(GOLD_FILE), goldJson(generated.gold()), StandardCharsets.UTF_8);
    }

    static Generated generate() {
        Random random = new Random(SEED);
        List<String> policies = IntStream.range(0, 400)
                .mapToObj(i -> String.format("0026%06d", random.nextInt(1_000_000))).toList();
        List<String> agents = IntStream.range(0, 60)
                .mapToObj(i -> String.format("%06d", 1 + random.nextInt(900))).toList();
        List<GoldRow> gold = new ArrayList<>();

        Sheet note = new Sheet(NOTE_SHEET, List.of(
                cells("본 파일은 TER-011 평가용 합성 명세입니다. 실제 회사·개인정보가 없습니다."),
                cells("금액이 음수인 행은 차감(환수) 금액입니다."),
                cells("2026년 10월 지급 예정분은 포함하지 않습니다.")));

        List<List<Object>> fee = new ArrayList<>();
        fee.add(cells("가상A생명 2026년 9월 수수료 지급명세 (단위: 원)"));
        List<Object> header = cells("마감년월", "증권번호", "모집자코드", "상품코드", "회차", "수수료항목", "구분", "금액");
        long feeTotal = 0;
        for (int start = 0; start < FEE_DATA_ROWS; start += BLOCK_ROWS) {
            fee.add(header);
            long subtotal = 0;
            for (int i = start; i < Math.min(start + BLOCK_ROWS, FEE_DATA_ROWS); i++) {
                boolean deduction = random.nextInt(100) < 12;
                long won = (1 + random.nextInt(deduction ? 100_000 : 200_000)) * 10L;
                long signed = deduction ? -won : won;
                GoldRow row = new GoldRow(FEE_SHEET, fee.size() + 1, MONTH, pick(random, policies), pick(random, agents),
                        pick(random, PRODUCTS), 1 + random.nextInt(24),
                        pick(random, deduction ? DEDUCTION_ITEMS : PAYMENT_ITEMS), deduction ? "DEDUCTION" : "PAYMENT", won);
                gold.add(row);
                fee.add(cells("202609", row.policyNo(), row.agentCode(), row.productCode(), (long) row.installment(),
                        row.itemName(), deduction ? "차감" : "지급", signed));
                subtotal += signed;
            }
            fee.add(cells("소계", null, null, null, null, null, null, subtotal));
            feeTotal += subtotal;
        }
        fee.add(cells("합계", null, null, null, null, null, null, feeTotal));

        List<List<Object>> thousand = new ArrayList<>();
        thousand.add(cells("(단위: 천원) 2026년 9월 환수·차감 및 시책 정산"));
        thousand.add(cells("정산월", "증권번호", "설계사코드", "상품", "회차", "항목", "금액(천원)"));
        BigDecimal thousandTotal = BigDecimal.ZERO;
        for (int i = 0; i < THOUSAND_DATA_ROWS; i++) {
            boolean deduction = random.nextInt(100) < 60;
            BigDecimal value = BigDecimal.valueOf(1 + random.nextInt(3_000));
            if (random.nextInt(5) == 0) {
                value = value.add(new BigDecimal("0.5"));
            }
            BigDecimal signed = deduction ? value.negate() : value;
            GoldRow row = new GoldRow(THOUSAND_SHEET, thousand.size() + 1, MONTH, pick(random, policies), pick(random, agents),
                    pick(random, PRODUCTS), 1 + random.nextInt(24), deduction ? pick(random, DEDUCTION_ITEMS) : "시책",
                    deduction ? "DEDUCTION" : "PAYMENT", value.movePointRight(3).longValueExact());
            gold.add(row);
            thousand.add(cells("2026.09", row.policyNo(), row.agentCode(), row.productCode(), (long) row.installment(),
                    row.itemName(), signed));
            thousandTotal = thousandTotal.add(signed);
        }
        thousand.add(cells("합계", null, null, null, null, null, thousandTotal));

        List<Sheet> sheets = List.of(note, new Sheet(FEE_SHEET, fee), new Sheet(THOUSAND_SHEET, thousand));
        Meta meta = new Meta(DATASET_ID, GENERATOR_VERSION, SEED, "가상A생명", MONTH, XLSX_FILE,
                "합성 데이터. row 는 엑셀 행 번호(1부터). amount 는 원 단위 양수, 부호는 cashflowType 으로 나타낸다.");
        return new Generated(new Gold(meta, List.of(total(gold, FEE_SHEET, "원"), total(gold, THOUSAND_SHEET, "천원")), gold), sheets);
    }

    static Gold readCommittedGold() {
        try {
            return MAPPER.readValue(RESOURCE_DIR.resolve(GOLD_FILE).toFile(), Gold.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static SheetTotal total(List<GoldRow> rows, String sheet, String unit) {
        List<GoldRow> inSheet = rows.stream().filter(row -> row.sheet().equals(sheet)).toList();
        long payment = inSheet.stream().filter(row -> row.cashflowType().equals("PAYMENT")).mapToLong(GoldRow::amount).sum();
        long deduction = inSheet.stream().filter(row -> row.cashflowType().equals("DEDUCTION")).mapToLong(GoldRow::amount).sum();
        return new SheetTotal(sheet, unit, inSheet.size(), payment, deduction, payment - deduction);
    }

    private static <T> T pick(Random random, List<T> values) {
        return values.get(random.nextInt(values.size()));
    }

    private static List<Object> cells(Object... values) {
        return Arrays.asList(values);
    }

    /** 리뷰·diff 를 위해 정답 행을 한 줄에 하나씩 쓴다. */
    static String goldJson(Gold gold) throws IOException {
        return "{\n  \"meta\": " + MAPPER.writeValueAsString(gold.meta())
                + ",\n  \"totals\": [\n    " + joinJson(gold.totals())
                + "\n  ],\n  \"rows\": [\n    " + joinJson(gold.rows()) + "\n  ]\n}\n";
    }

    private static String joinJson(List<?> values) throws IOException {
        List<String> lines = new ArrayList<>();
        for (Object value : values) {
            lines.add(MAPPER.writeValueAsString(value));
        }
        return String.join(",\n    ", lines);
    }

    // ---------------------------------------------------------------- 최소 xlsx(SpreadsheetML) — 의존성 없이 쓰기

    /** 압축 바이트는 JDK·zlib 마다 다를 수 있어, 비교는 이 압축 전 항목으로 한다. */
    static Map<String, byte[]> xlsxEntries(List<Sheet> sheets) {
        List<String> strings = new ArrayList<>();
        Map<String, Integer> index = new LinkedHashMap<>();
        Map<String, byte[]> entries = new LinkedHashMap<>();
        String main = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
        String rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
        String pkgRel = "http://schemas.openxmlformats.org/package/2006/relationships";
        String head = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";

        StringBuilder types = new StringBuilder(head + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>");
        StringBuilder workbook = new StringBuilder(head + "<workbook xmlns=\"" + main + "\" xmlns:r=\"" + rel + "\"><sheets>");
        StringBuilder workbookRels = new StringBuilder(head + "<Relationships xmlns=\"" + pkgRel + "\">");
        Map<String, byte[]> sheetParts = new LinkedHashMap<>();
        for (int s = 1; s <= sheets.size(); s++) {
            Sheet sheet = sheets.get(s - 1);
            types.append("<Override PartName=\"/xl/worksheets/sheet").append(s)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
            workbook.append("<sheet name=\"").append(escape(sheet.name())).append("\" sheetId=\"").append(s)
                    .append("\" r:id=\"rId").append(s).append("\"/>");
            workbookRels.append("<Relationship Id=\"rId").append(s).append("\" Type=\"").append(rel)
                    .append("/worksheet\" Target=\"worksheets/sheet").append(s).append(".xml\"/>");
            StringBuilder xml = new StringBuilder(head + "<worksheet xmlns=\"" + main + "\"><sheetData>");
            for (int r = 1; r <= sheet.rows().size(); r++) {
                xml.append("<row r=\"").append(r).append("\">");
                List<Object> row = sheet.rows().get(r - 1);
                for (int c = 0; c < row.size(); c++) {
                    Object value = row.get(c);
                    String ref = (char) ('A' + c) + Integer.toString(r);
                    if (value instanceof String text) {
                        int id = index.computeIfAbsent(text, key -> {
                            strings.add(key);
                            return strings.size() - 1;
                        });
                        xml.append("<c r=\"").append(ref).append("\" t=\"s\"><v>").append(id).append("</v></c>");
                    } else if (value instanceof Number number) {
                        xml.append("<c r=\"").append(ref).append("\"><v>")
                                .append(new BigDecimal(number.toString()).toPlainString()).append("</v></c>");
                    }
                }
                xml.append("</row>");
            }
            sheetParts.put("xl/worksheets/sheet" + s + ".xml", xml.append("</sheetData></worksheet>").toString().getBytes(StandardCharsets.UTF_8));
        }
        workbookRels.append("<Relationship Id=\"rId").append(sheets.size() + 1).append("\" Type=\"").append(rel)
                .append("/sharedStrings\" Target=\"sharedStrings.xml\"/></Relationships>");
        String sst = strings.stream().map(text -> "<si><t xml:space=\"preserve\">" + escape(text) + "</t></si>")
                .collect(Collectors.joining("", head + "<sst xmlns=\"" + main + "\" count=\"" + strings.size()
                        + "\" uniqueCount=\"" + strings.size() + "\">", "</sst>"));

        entries.put("[Content_Types].xml", types.append("</Types>").toString().getBytes(StandardCharsets.UTF_8));
        entries.put("_rels/.rels", (head + "<Relationships xmlns=\"" + pkgRel + "\"><Relationship Id=\"rId1\" Type=\"" + rel
                + "/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>").getBytes(StandardCharsets.UTF_8));
        entries.put("xl/workbook.xml", workbook.append("</sheets></workbook>").toString().getBytes(StandardCharsets.UTF_8));
        entries.put("xl/_rels/workbook.xml.rels", workbookRels.toString().getBytes(StandardCharsets.UTF_8));
        entries.put("xl/sharedStrings.xml", sst.getBytes(StandardCharsets.UTF_8));
        entries.putAll(sheetParts);
        return entries;
    }

    static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTimeLocal(LocalDateTime.of(2026, 10, 23, 0, 0)); // 시간대와 무관하게 같은 파일
                zip.putNextEntry(zipEntry);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    static Map<String, byte[]> unzip(InputStream in) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
