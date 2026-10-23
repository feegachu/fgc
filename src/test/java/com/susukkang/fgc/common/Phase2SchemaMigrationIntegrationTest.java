package com.susukkang.fgc.common;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 이슈 #632: V58 2차 신규 테이블·statement_batch 판 관리 제약을 실제 PostgreSQL에서 확인한다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Phase2SchemaMigrationIntegrationTest {
    private static final String PASSWORD = "fgc_test_only";
    private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("fgc_phase2").withUsername("fgc").withPassword(PASSWORD);

    @BeforeAll
    void setup() {
        postgres.start();
        var flyway = Flyway.configure().dataSource(postgres.getJdbcUrl(), "fgc", PASSWORD).schemas("fgc")
                .locations("classpath:db/migration", "classpath:db/demo").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
    }

    @AfterAll
    void stop() {
        postgres.stop();
    }

    @Test
    void createsAllSixteenPhase2Tables() throws Exception {
        try (var conn = connect()) {
            assertThat(scalar(conn, """
                    SELECT count(*) FROM information_schema.tables WHERE table_schema = 'fgc' AND table_name IN (
                    'intake_document','intake_draft_row','intake_confirm_request','generated_document',
                    'clawback_notice_delivery','compare_product_group','commission_grade','compare_explanation',
                    'compare_confirmation_receipt','notice','inquiry','inquiry_action','library_item',
                    'knowledge_chunk','agent_contract_check','ai_call_log')""")).isEqualTo(16L);
            assertThat(scalar(conn, "SELECT count(*) FROM fgc.flyway_schema_history WHERE script = 'V58__phase2_intake_compare_tables.sql' AND success"))
                    .isEqualTo(1L);
        }
    }

    @Test
    void existingStatementBatchRowsAreVersionOneWithoutPredecessor() throws Exception {
        try (var conn = connect()) {
            assertThat(scalar(conn, "SELECT count(*) FROM fgc.statement_batch")).isGreaterThan(0L);
            assertThat(scalar(conn, """
                    SELECT count(*) FROM fgc.statement_batch
                    WHERE version_no <> 1 OR supersedes_statement_batch_id IS NOT NULL""")).isZero();
        }
    }

    @Test
    void rejectsSupersedingVersionWithoutPredecessor() throws Exception {
        try (var conn = connect()) {
            conn.setAutoCommit(false);
            try {
                assertThatThrownBy(() -> execute(conn, """
                        INSERT INTO fgc.statement_batch(insurer_id, settlement_month, statement_type, external_statement_no,
                                                        received_on, source_ref, version_no)
                        SELECT insurer_id, settlement_month, statement_type, 'V2-NO-PRED', received_on, 'test', 2
                        FROM fgc.statement_batch LIMIT 1"""))
                        .hasMessageContaining("ck_statement_batch_supersedes");
            } finally {
                conn.rollback();
            }
        }
    }

    @Test
    void acceptsSupersedingVersionAndRejectsSecondSuccessor() throws Exception {
        try (var conn = connect()) {
            conn.setAutoCommit(false);
            try {
                String insert = """
                        INSERT INTO fgc.statement_batch(insurer_id, settlement_month, statement_type, external_statement_no,
                                                        received_on, source_ref, version_no, supersedes_statement_batch_id,
                                                        data_origin)
                        SELECT insurer_id, settlement_month, statement_type, external_statement_no, received_on, 'test',
                               %d, statement_batch_id, 'AI_INTAKE'
                        FROM fgc.statement_batch ORDER BY statement_batch_id LIMIT 1""";
                execute(conn, insert.formatted(2));
                execute(conn, "UPDATE fgc.statement_batch SET status = 'SUPERSEDED' WHERE version_no = 1 "
                        + "AND statement_batch_id = (SELECT min(statement_batch_id) FROM fgc.statement_batch)");
                // 같은 이전 판을 대체하는 두 번째 후속본은 거절된다
                assertThatThrownBy(() -> execute(conn, insert.formatted(3)))
                        .hasMessageContaining("uq_statement_batch");
            } finally {
                conn.rollback();
            }
        }
    }

    @Test
    void storesDifferentRankSetsForSameProductAndDate() throws Exception {
        try (var conn = connect()) {
            conn.setAutoCommit(false);
            try {
                seedGradeParents(conn);
                insertGrade(conn, "'ABC'", 1);
                insertGrade(conn, "'ABCD'", 1);
                assertThat(scalar(conn, "SELECT count(*) FROM fgc.commission_grade")).isEqualTo(2L);
            } finally {
                conn.rollback();
            }
        }
    }

    @Test
    void rejectsDuplicateRankSetForSameProductAndDate() throws Exception {
        try (var conn = connect()) {
            conn.setAutoCommit(false);
            try {
                seedGradeParents(conn);
                insertGrade(conn, "'ABC'", 1);
                assertThatThrownBy(() -> insertGrade(conn, "'ABC'", 2)).hasMessageContaining("uq_commission_grade");
            } finally {
                conn.rollback();
            }
        }
    }

    @Test
    void rejectsSecondUnrankedGradeForSameProductAndDate() throws Exception {
        try (var conn = connect()) {
            conn.setAutoCommit(false);
            try {
                seedGradeParents(conn);
                insertGrade(conn, "NULL", null);
                assertThatThrownBy(() -> insertGrade(conn, "NULL", null)).hasMessageContaining("uq_commission_grade");
            } finally {
                conn.rollback();
            }
        }
    }

    @Test
    void installsHistoryGuardsAndRankSetColumns() throws Exception {
        try (var conn = connect()) {
            assertThat(scalar(conn, """
                    SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal AND tgname IN (
                    'trg_intake_draft_row_confirmed_immutable','trg_inquiry_action_append_only',
                    'trg_clawback_notice_delivery_append_only','trg_compare_confirmation_receipt_append_only',
                    'trg_intake_document_updated_at','trg_compare_explanation_updated_at','trg_notice_updated_at')"""))
                    .isEqualTo(7L);
            assertThat(scalar(conn, """
                    SELECT count(*) FROM information_schema.columns WHERE table_schema = 'fgc'
                    AND table_name = 'compare_explanation' AND column_name IN ('rank_set_key','rank_as_of')"""))
                    .isEqualTo(2L);
        }
    }

    private void seedGradeParents(Connection conn) throws SQLException {
        execute(conn, """
                INSERT INTO fgc.compare_product_group(group_code, insurance_sector, major_class, minor_class_name)
                VALUES ('TEST-GRP', 'LIFE', 'PROTECTION', 'test')""");
    }

    private void insertGrade(Connection conn, String rankSetKey, Integer rankNo) throws SQLException {
        execute(conn, """
                INSERT INTO fgc.commission_grade(product_offering_id, compare_product_group_id, underwriting_type,
                                                 refund_type, grade_code, rank_no, rank_set_key, data_as_of)
                SELECT (SELECT min(product_offering_id) FROM fgc.product_offering),
                       (SELECT compare_product_group_id FROM fgc.compare_product_group WHERE group_code = 'TEST-GRP'),
                       'STANDARD', 'STANDARD', 'NORMAL', %s, %s, DATE '2026-09-01'"""
                .formatted(rankNo == null ? "NULL" : rankNo.toString(), rankSetKey));
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), "fgc", PASSWORD);
    }

    private static void execute(Connection conn, String sql) throws SQLException {
        try (var st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static Long scalar(Connection conn, String sql) throws SQLException {
        try (var st = conn.createStatement(); var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
