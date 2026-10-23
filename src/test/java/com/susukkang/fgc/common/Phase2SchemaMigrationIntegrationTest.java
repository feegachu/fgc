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
                assertThatThrownBy(() -> execute(conn, insert.formatted(2)))
                        .hasMessageContaining("uq_statement_batch");
            } finally {
                conn.rollback();
            }
        }
    }

    @Test
    void rejectsSuccessorWithSkippedVersionOrDifferentBusinessKey() throws Exception {
        try (var conn = connect()) {
            conn.setAutoCommit(false);
            try {
                String insert = """
                        INSERT INTO fgc.statement_batch(insurer_id, settlement_month, statement_type, external_statement_no,
                                                        received_on, source_ref, version_no, supersedes_statement_batch_id)
                        SELECT insurer_id, settlement_month, statement_type, %s, received_on, 'test',
                               %d, statement_batch_id
                        FROM fgc.statement_batch ORDER BY statement_batch_id LIMIT 1""";
                assertRejected(conn, insert.formatted("external_statement_no", 5));
                assertRejected(conn, insert.formatted("'OTHER-NO'", 2));
                execute(conn, insert.formatted("external_statement_no", 2));
                // 적재 뒤 키·판 번호 컬럼을 바꾸는 우회도 막는다
                assertRejected(conn, "UPDATE fgc.statement_batch SET version_no = 5 WHERE version_no = 2");
                assertRejected(conn, "UPDATE fgc.statement_batch SET external_statement_no = 'OTHER-NO' WHERE version_no = 2");
                // 후속본이 있는 이전 판의 업무키도 바꿀 수 없다
                assertRejected(conn, "UPDATE fgc.statement_batch SET external_statement_no = 'OTHER-NO' "
                        + "WHERE statement_batch_id = (SELECT supersedes_statement_batch_id FROM fgc.statement_batch "
                        + "WHERE version_no = 2)");
            } finally {
                conn.rollback();
            }
        }
    }

    @Test
    void rejectsRevivingSupersededStatementBatch() throws Exception {
        try (var conn = connect()) {
            conn.setAutoCommit(false);
            try {
                String min = " WHERE statement_batch_id = (SELECT min(statement_batch_id) FROM fgc.statement_batch)";
                execute(conn, "UPDATE fgc.statement_batch SET status = 'SUPERSEDED'" + min);
                assertRejected(conn, "UPDATE fgc.statement_batch SET status = 'AVAILABLE'" + min);
            } finally {
                conn.rollback();
            }
        }
    }

    @Test
    void rejectsChangesToGeneratedDocument() throws Exception {
        try (var conn = connect()) {
            conn.setAutoCommit(false);
            try {
                seedIntakeDocument(conn);
                assertRejected(conn, "UPDATE fgc.generated_document SET content_hash = 'changed'");
                assertRejected(conn, "DELETE FROM fgc.generated_document");
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

    @Test
    void confirmsDraftRowInOneStatementThenFreezesIt() throws Exception {
        try (var conn = connect()) {
            conn.setAutoCommit(false);
            try {
                seedIntakeDocument(conn);
                insertDraftRow(conn, 1, "REVIEW_REQUIRED");
                insertDraftRow(conn, 2, "REVIEW_REQUIRED");
                execute(conn, """
                        INSERT INTO fgc.intake_draft_row(intake_document_id, row_no, source_location, original_fields,
                                                         row_status, exclude_reason)
                        SELECT intake_document_id, 3, '{}', '{}', 'EXCLUDED', 'test' FROM fgc.intake_document LIMIT 1""");
                // 정상 경로: 확정은 ck_intake_draft_row_confirmed 때문에 한 문장으로 바꾼다
                execute(conn, """
                        UPDATE fgc.intake_draft_row SET row_status = 'CONFIRMED', confirmed_at = now(),
                               result_entity_type = 'STATEMENT_LINE', result_entity_id = 1 WHERE row_no = 1""");
                assertRejected(conn, "UPDATE fgc.intake_draft_row SET edited_fields = '{}' WHERE row_no = 1");
                assertRejected(conn, "DELETE FROM fgc.intake_draft_row WHERE row_no = 1");
                // 남은 행은 계속 고치거나 제외할 수 있다
                execute(conn, "UPDATE fgc.intake_draft_row SET edited_fields = '{}' WHERE row_no = 2");
                execute(conn, "UPDATE fgc.intake_draft_row SET exclude_reason = 'again' WHERE row_no = 3");
                execute(conn, "DELETE FROM fgc.intake_draft_row WHERE row_no = 2");
            } finally {
                conn.rollback();
            }
        }
    }

    @Test
    void protectsClawbackNoticeHistory() throws Exception {
        try (var conn = connect()) {
            conn.setAutoCommit(false);
            try {
                seedIntakeDocument(conn);
                String insertFirst = """
                        INSERT INTO fgc.clawback_notice_delivery(clawback_case_id, generated_document_id, recipient_agent_id,
                                                                 notice_date, notice_kind, delivery_method, evidence_ref,
                                                                 registered_by)
                        SELECT (SELECT clawback_case_id FROM fgc.clawback_case WHERE case_no = 'TEST-CASE'),
                               (SELECT generated_document_id FROM fgc.generated_document WHERE document_no = 'TEST-DOC'),
                               (SELECT min(agent_id) FROM fgc.agent), DATE '2026-09-01', 'FIRST', 'POST', 'ref',
                               (SELECT min(user_id) FROM fgc.app_user)""";
                execute(conn, insertFirst);
                assertRejected(conn, "DELETE FROM fgc.clawback_notice_delivery");
                assertRejected(conn, "UPDATE fgc.clawback_notice_delivery SET evidence_ref = 'x'");
                assertThatThrownBy(() -> execute(conn, insertFirst)).isInstanceOf(SQLException.class)
                        .hasMessageContaining("uq_clawback_notice_first");
            } finally {
                conn.rollback();
            }
        }
    }

    @Test
    void rejectsChangesToReceiptAndInquiryActionHistory() throws Exception {
        try (var conn = connect()) {
            conn.setAutoCommit(false);
            try {
                seedIntakeDocument(conn);
                execute(conn, """
                        INSERT INTO fgc.compare_confirmation_receipt(generated_document_id, received_on,
                                                                     receipt_confirmed_by, paper_storage_ref)
                        SELECT generated_document_id, DATE '2026-09-01', (SELECT min(user_id) FROM fgc.app_user), 'ref'
                        FROM fgc.generated_document WHERE document_no = 'TEST-DOC'""");
                assertRejected(conn, "UPDATE fgc.compare_confirmation_receipt SET paper_storage_ref = 'x'");
                assertRejected(conn, "DELETE FROM fgc.compare_confirmation_receipt");

                execute(conn, """
                        INSERT INTO fgc.inquiry(inquiry_kind, target_type, target_id, agent_id, content, due_date, created_by)
                        SELECT 'INQUIRY', 'CONTRACT', 1, (SELECT min(agent_id) FROM fgc.agent), 'q', DATE '2026-09-10',
                               (SELECT min(user_id) FROM fgc.app_user)""");
                execute(conn, """
                        INSERT INTO fgc.inquiry_action(inquiry_id, action_seq, action_type, acted_by)
                        SELECT min(inquiry_id), 1, 'START_REVIEW', (SELECT min(user_id) FROM fgc.app_user)
                        FROM fgc.inquiry""");
                assertRejected(conn, "UPDATE fgc.inquiry_action SET message = 'x'");
                assertRejected(conn, "DELETE FROM fgc.inquiry_action");
            } finally {
                conn.rollback();
            }
        }
    }

    @Test
    void requiresRankSetAndRankAsOfTogetherAndTouchesUpdatedAt() throws Exception {
        try (var conn = connect()) {
            conn.setAutoCommit(false);
            try {
                seedGradeParents(conn);
                String insert = """
                        INSERT INTO fgc.compare_explanation(grade_data_as_of, agent_id, compare_product_group_id,
                                                            recommended_offering_id, compared_offering_ids, rank_set_key,
                                                            rank_as_of, selection_criteria, recommendation_reason, fee_level_note)
                        SELECT DATE '2026-09-01', (SELECT min(agent_id) FROM fgc.agent),
                               (SELECT compare_product_group_id FROM fgc.compare_product_group WHERE group_code = 'TEST-GRP'),
                               (SELECT min(product_offering_id) FROM fgc.product_offering), '[1]', %s, %s, 'c', 'r', 'f'""";
                assertThatThrownBy(() -> execute(conn, insert.formatted("'ABC'", "NULL")))
                        .isInstanceOf(SQLException.class).hasMessageContaining("ck_compare_explanation_rank");
            } finally {
                conn.rollback();
            }
        }
        try (var conn = connect()) {
            conn.setAutoCommit(false);
            try {
                seedGradeParents(conn);
                execute(conn, """
                        INSERT INTO fgc.compare_explanation(grade_data_as_of, agent_id, compare_product_group_id,
                                                            recommended_offering_id, compared_offering_ids, selection_criteria,
                                                            recommendation_reason, fee_level_note)
                        SELECT DATE '2026-09-01', (SELECT min(agent_id) FROM fgc.agent),
                               (SELECT compare_product_group_id FROM fgc.compare_product_group WHERE group_code = 'TEST-GRP'),
                               (SELECT min(product_offering_id) FROM fgc.product_offering), '[1]', 'c', 'r', 'f'""");
                execute(conn, "UPDATE fgc.compare_explanation SET fee_level_note = 'g'");
                assertThat(scalar(conn, "SELECT count(*) FROM fgc.compare_explanation WHERE updated_at > created_at"))
                        .isEqualTo(1L);
            } finally {
                conn.rollback();
            }
        }
    }

    /** 실패한 문장이 트랜잭션 전체를 중단시키지 않도록 savepoint 안에서 거절을 확인한다. */
    private static void assertRejected(Connection conn, String sql) throws SQLException {
        var savepoint = conn.setSavepoint();
        try {
            assertThatThrownBy(() -> execute(conn, sql)).isInstanceOf(SQLException.class);
        } finally {
            conn.rollback(savepoint);
        }
    }

    private void seedIntakeDocument(Connection conn) throws SQLException {
        execute(conn, """
                INSERT INTO fgc.intake_document(original_file_name, storage_key, media_type, file_size_bytes, content_hash,
                                                uploaded_by)
                SELECT 'test.pdf', 'test-key', 'application/pdf', 1, 'test-hash-guard', min(user_id) FROM fgc.app_user""");
        execute(conn, """
                INSERT INTO fgc.generated_document(document_no, document_kind, source_entity_type, source_entity_id,
                                                   storage_key, content_hash, created_by)
                SELECT 'TEST-DOC', 'CLAWBACK_NOTICE', 'CLAWBACK_CASE', 1, 'doc-key', 'doc-hash', min(user_id)
                FROM fgc.app_user""");
        execute(conn, """
                INSERT INTO fgc.clawback_case(contract_id, policy_version_id, case_no)
                SELECT (SELECT min(contract_id) FROM fgc.insurance_contract),
                       (SELECT min(policy_version_id) FROM fgc.policy_version), 'TEST-CASE'""");
    }

    private void insertDraftRow(Connection conn, int rowNo, String status) throws SQLException {
        execute(conn, """
                INSERT INTO fgc.intake_draft_row(intake_document_id, row_no, source_location, original_fields, row_status)
                SELECT intake_document_id, %d, '{}', '{}', '%s' FROM fgc.intake_document LIMIT 1"""
                .formatted(rowNo, status));
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
