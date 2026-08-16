package com.susukkang.fgc.base.mapper;

import com.susukkang.fgc.base.dto.InsurerRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class InsurerMapperIntegrationTest {

    @Autowired
    private InsurerMapper insurerMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void includesInactiveInsurers() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String prefix = "INS-" + suffix;
        insertInsurer(prefix + "-A", "테스트활성생명-" + suffix, "LIFE", true);
        insertInsurer(prefix + "-I", "테스트중지손보-" + suffix, "NON_LIFE", false);

        List<InsurerRow> rows = insurerMapper.selectInsurers(prefix, 0, 20);

        assertThat(rows).extracting(InsurerRow::insurerCode)
                .containsExactly(prefix + "-A", prefix + "-I");
        assertThat(rows.get(0).activeYn()).isTrue();
        assertThat(rows.get(1).activeYn()).isFalse();
        assertThat(rows.get(1).insurerType()).isEqualTo("NON_LIFE");
        assertThat(insurerMapper.countInsurers(prefix)).isEqualTo(2);
    }

    @Test
    void searchesCodeOrNameCaseInsensitivelyAndAppliesStablePaging() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String prefix = "pg-" + suffix;
        for (int index = 21; index >= 1; index--) {
            insertInsurer(
                    "%s-%02d".formatted(prefix, index),
                    "보험회사 %02d".formatted(index),
                    "LIFE",
                    true
            );
        }

        List<InsurerRow> secondPage = insurerMapper.selectInsurers(prefix.toUpperCase(), 20, 20);

        assertThat(insurerMapper.countInsurers(prefix.toUpperCase())).isEqualTo(21);
        assertThat(secondPage).singleElement()
                .extracting(InsurerRow::insurerCode)
                .isEqualTo(prefix + "-21");
    }

    @Test
    void searchesByInsurerName() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String uniqueName = "가상테스트생명-" + suffix;
        String code = "NM-" + suffix;
        insertInsurer(code, uniqueName, "LIFE", true);

        List<InsurerRow> rows = insurerMapper.selectInsurers("테스트생명-" + suffix, 0, 20);

        assertThat(rows).singleElement()
                .extracting(InsurerRow::insurerCode)
                .isEqualTo(code);
    }

    @Test
    void returnsEmptyListWhenNoMatch() {
        String noMatchKeyword = "no-match-" + UUID.randomUUID();

        assertThat(insurerMapper.selectInsurers(noMatchKeyword, 0, 20)).isEmpty();
        assertThat(insurerMapper.countInsurers(noMatchKeyword)).isZero();
    }

    private long insertInsurer(String code, String name, String type, boolean activeYn) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.insurer (
                    insurer_code,
                    insurer_name,
                    insurer_type,
                    active_yn
                ) VALUES (?, ?, ?, ?)
                RETURNING insurer_id
                """,
                Long.class,
                code,
                name,
                type,
                activeYn
        );
    }
}
