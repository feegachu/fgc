package com.susukkang.fgc.common.web;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Arrays;

/** 목록 다운로드용 UTF-8 BOM CSV 생성기. */
public final class CsvExportWriter {

    private CsvExportWriter() {
    }

    public static byte[] write(List<String> headers, List<List<?>> rows) {
        StringBuilder csv = new StringBuilder("\uFEFF");
        appendRow(csv, headers);
        for (List<?> row : rows) {
            appendRow(csv, row);
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static List<?> row(Object... values) {
        return Arrays.asList(values);
    }

    private static void appendRow(StringBuilder csv, List<?> values) {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                csv.append(',');
            }
            String value = values.get(index) == null ? "" : String.valueOf(values.get(index));
            if (startsWithFormulaOperator(value)) {
                value = "'" + value;
            }
            csv.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        csv.append("\r\n");
    }

    /** Spreadsheet programs must treat exported user text as text, not as a formula (SRC-028). */
    private static boolean startsWithFormulaOperator(String value) {
        if (value.isEmpty()) {
            return false;
        }
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@';
    }
}
