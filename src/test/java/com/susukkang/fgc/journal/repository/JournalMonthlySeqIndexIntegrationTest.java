package com.susukkang.fgc.journal.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #673 월 채번 쿼리와 V82 인덱스. 쿼리는 복제하지 않고 Repository의 @Query 문자열을 그대로 쓴다.
 * 행, 통계, 인덱스 재생성은 테스트 트랜잭션과 함께 되돌린다.
 */
@SpringBootTest
@Transactional
class JournalMonthlySeqIndexIntegrationTest {

    private static final String INDEX = "idx_journal_header_monthly_seq";

    @Autowired
    private JournalHeaderRepository headerRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void nextSequenceReadsTheIndexInCustomAndGenericPlans() throws Exception {
        // 옛 상한을 넘는 행 수로, 다른 달 행도 함께 넣어 달 조건까지 인덱스로 거르는지 본다.
        seedMonth("3100-01", 12_000);
        seedMonth("3100-02", 3_000);
        jdbcTemplate.execute("ANALYZE fgc.journal_header");
        String sql = JournalHeaderRepository.class.getMethod("findNextJournalSeq", String.class, String.class)
                .getAnnotation(Query.class).value().replace(":year", "$1").replace(":month", "$2");

        // JDBC 드라이버는 같은 문장을 반복하면 서버 준비문으로 바꾸므로 두 계획을 모두 본다.
        jdbcTemplate.execute("PREPARE next_seq (varchar, varchar) AS " + sql);
        String customPlan = planIn("force_custom_plan");
        String genericPlan = planIn("force_generic_plan");
        jdbcTemplate.execute("DEALLOCATE next_seq");

        assertThat(customPlan).contains(INDEX).doesNotContain("Seq Scan");
        assertThat(genericPlan).contains(INDEX).doesNotContain("Seq Scan");
        assertThat(headerRepository.findNextJournalSeq("3100", "01")).isEqualTo(12_001);
        assertThat(headerRepository.findNextJournalSeq("3100", "02")).isEqualTo(3_001);
        assertThat(headerRepository.findNextJournalSeq("3100", "03")).isEqualTo(1);
    }

    @Test
    void migrationAppliesOverExistingNumbersOutsideTheFormat() throws Exception {
        for (String journalNo : List.of("TEST-SEARCH-0001", "JV-TEST-POSTED-0001", "JV-3101-01-ABC",
                "JV-3101-01-12345678901", "JV-3101-01-0007")) {
            jdbcTemplate.update("""
                    INSERT INTO fgc.journal_header
                        (journal_no, journal_date, journal_type, source_entity_type, source_entity_id)
                    VALUES (?, DATE '3101-01-15', 'ADJUSTMENT', 'INDEX_TEST', ?)
                    """, journalNo, journalNo);
        }

        jdbcTemplate.execute("DROP INDEX fgc." + INDEX);
        jdbcTemplate.execute(new ClassPathResource("db/migration/V82__journal_monthly_seq_index.sql")
                .getContentAsString(StandardCharsets.UTF_8));

        assertThat(headerRepository.findNextJournalSeq("3101", "01")).isEqualTo(8);
    }

    private void seedMonth(String yearMonth, int rows) {
        jdbcTemplate.update("""
                INSERT INTO fgc.journal_header
                    (journal_no, journal_date, journal_type, source_entity_type, source_entity_id)
                SELECT 'JV-' || ? || '-' || lpad(n::text, greatest(length(n::text), 4), '0'),
                       DATE '3100-01-15', 'ADJUSTMENT', 'INDEX_TEST', ? || '-' || n
                  FROM generate_series(1, ?) n
                """, yearMonth, yearMonth, rows);
    }

    private String planIn(String planCacheMode) {
        jdbcTemplate.execute("SET LOCAL plan_cache_mode = " + planCacheMode);
        return String.join("\n", jdbcTemplate.queryForList("EXPLAIN EXECUTE next_seq('3100', '01')", String.class));
    }
}
