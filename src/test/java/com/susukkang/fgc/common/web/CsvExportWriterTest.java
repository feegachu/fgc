package com.susukkang.fgc.common.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvExportWriterTest {

    @Test
    void prefixesSpreadsheetFormulaOperatorsWithApostrophe() {
        byte[] bytes = CsvExportWriter.write(
                List.of("value"),
                List.of(
                        CsvExportWriter.row("=1+1"),
                        CsvExportWriter.row("+1+1"),
                        CsvExportWriter.row("-1+1"),
                        CsvExportWriter.row("@SUM(1,1)")));

        String csv = new String(bytes, StandardCharsets.UTF_8);

        assertThat(csv)
                .contains("\"'=1+1\"")
                .contains("\"'+1+1\"")
                .contains("\"'-1+1\"")
                .contains("\"'@SUM(1,1)\"");
    }

    @Test
    void preservesOrdinaryValuesAndEscapesQuotes() {
        byte[] bytes = CsvExportWriter.write(
                List.of("value"),
                List.of(CsvExportWriter.row("normal \"text\""), CsvExportWriter.row((Object) null)));

        String csv = new String(bytes, StandardCharsets.UTF_8);

        assertThat(csv)
                .contains("\"normal \"\"text\"\"\"")
                .contains("\r\n\"\"\r\n");
    }
}
