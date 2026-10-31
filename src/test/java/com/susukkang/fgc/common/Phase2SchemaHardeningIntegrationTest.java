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

/**
 * V62: V58 판 승계·이력 가드 보완을 실제 PostgreSQL에서 확인한다.
 * 커밋 시점 검사와 두 연결의 경합을 보려고 데이터를 실제로 커밋하므로 V58 테스트와 컨테이너를 나눈다.
 * 테스트마다 아직 대체되지 않은 다른 명세 묶음을 골라 서로 영향을 주지 않는다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Phase2SchemaHardeningIntegrationTest {
    private static final String PASSWORD = "fgc_test_only";
    private static final String LOCK_TIMEOUT = "55P03";
    private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("fgc_phase2_hardening").withUsername("fgc").withPassword(PASSWORD);

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
    void everyMutableFgcFunctionPinsSearchPath() throws Exception {
        // SRC-050·V6 규칙: IMMUTABLE 순수 함수만 예외. 새 가드 함수가 고정을 빠뜨리면 여기서 걸린다.
        try (var conn = connect()) {
            assertThat(scalar(conn, """
                    SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                    WHERE n.nspname = 'fgc' AND p.prokind = 'f' AND p.provolatile <> 'i'
                      AND NOT EXISTS (SELECT 1 FROM unnest(coalesce(p.proconfig, '{}')) c WHERE c LIKE 'search_path=%')"""))
                    .isZero();
        }
    }

    @Test
    void commitRejectsSuccessorWhilePredecessorStaysValid() throws Exception {
        long v1 = freshBatch(false);
        try (var conn = connect()) {
            conn.setAutoCommit(false);
            insertSuccessor(conn, v1);
            assertThatThrownBy(conn::commit).hasMessageContaining("must be SUPERSEDED in the same transaction");
        }
        try (var conn = connect()) {
            assertThat(scalar(conn, "SELECT count(*) FROM fgc.statement_batch WHERE supersedes_statement_batch_id = " + v1))
                    .isZero();
        }
    }

    @Test
    void commitRejectsSupersededBatchWithoutSuccessor() throws Exception {
        long v1 = freshBatch(false);
        try (var conn = connect()) {
            conn.setAutoCommit(false);
            execute(conn, "UPDATE fgc.statement_batch SET status = 'SUPERSEDED' WHERE statement_batch_id = " + v1);
            assertThatThrownBy(conn::commit).hasMessageContaining("is SUPERSEDED without a successor");
        }
        try (var conn = connect()) {
            assertThat(status(conn, v1)).isEqualTo("AVAILABLE");
        }
    }

    @Test
    void fullReplacementCommitsOnlyAfterPreviousSourcesAreCancelled() throws Exception {
        long v1 = freshBatch(true);
        try (var conn = connect()) {
            conn.setAutoCommit(false);
            insertSuccessor(conn, v1);
            execute(conn, "UPDATE fgc.statement_batch SET status = 'SUPERSEDED' WHERE statement_batch_id = " + v1);
            // 이전 원천을 취소하지 않으면 분개 원천 조회가 이전 판을 다시 기표하므로 커밋을 막는다
            assertThatThrownBy(conn::commit).hasMessageContaining("still has CONFIRMED source rows");
        }
        try (var conn = connect()) {
            conn.setAutoCommit(false);
            supersede(conn, v1);
            conn.commit();
        }
        try (var conn = connect()) {
            long v2 = scalar(conn, "SELECT statement_batch_id FROM fgc.statement_batch WHERE supersedes_statement_batch_id = " + v1);
            assertThat(status(conn, v1)).isEqualTo("SUPERSEDED");
            assertThat(scalar(conn, "SELECT count(*) FROM fgc.commission_transaction WHERE status = 'CONFIRMED' "
                    + "AND statement_batch_id = " + v1)).isZero();
            // 판 사슬은 지울 수 없다 — 후속본을 지우면 이전 판만 SUPERSEDED 로 남아 명세가 사라진다
            assertThatThrownBy(() -> execute(conn, "DELETE FROM fgc.statement_batch WHERE statement_batch_id = " + v2))
                    .hasMessageContaining("part of a version chain");
            assertThatThrownBy(() -> execute(conn, "DELETE FROM fgc.statement_batch WHERE statement_batch_id = " + v1))
                    .hasMessageContaining("part of a version chain");
            // 대체된 판 아래 원천은 새로 확정할 수 없다
            long draft = insertDraftSource(conn, v1, "AFTER-SUPERSEDE");
            assertThatThrownBy(() -> confirm(conn, draft)).hasMessageContaining("cannot be confirmed");
            // 커밋된 전환 뒤 후속본을 거절하면 이전 판(복귀 불가)만 남아 명세가 대사에서 사라지므로 막는다(PR #690 리뷰)
            assertThatThrownBy(() -> execute(conn, "UPDATE fgc.statement_batch SET status = 'REJECTED' "
                    + "WHERE statement_batch_id = " + v2)).hasMessageContaining("cannot be REJECTED");
            assertThat(status(conn, v2)).isEqualTo("AVAILABLE");
            // 전환과 같은 트랜잭션에서 후속본을 거절해도 커밋이 막힌다
            long other = freshBatch(false);
            conn.setAutoCommit(false);
            insertSuccessor(conn, other);
            execute(conn, "UPDATE fgc.statement_batch SET status = 'SUPERSEDED' WHERE statement_batch_id = " + other);
            execute(conn, "UPDATE fgc.statement_batch SET status = 'REJECTED' WHERE supersedes_statement_batch_id = " + other);
            assertThatThrownBy(conn::commit).hasMessageContaining("cannot be REJECTED");
        }
    }

    @Test
    void businessKeyCannotChangeEvenWithoutSuccessor() throws Exception {
        long v1 = freshBatch(false);
        try (var conn = connect()) {
            assertThatThrownBy(() -> execute(conn, "UPDATE fgc.statement_batch SET external_statement_no = 'OTHER-NO' "
                    + "WHERE statement_batch_id = " + v1)).hasMessageContaining("are immutable");
            // 상태 변경은 계속 된다
            execute(conn, "UPDATE fgc.statement_batch SET status = 'VALIDATED' WHERE statement_batch_id = " + v1);
            execute(conn, "UPDATE fgc.statement_batch SET status = 'AVAILABLE' WHERE statement_batch_id = " + v1);
        }
    }

    @Test
    void concurrentSuccessorInsertAndPredecessorKeyChangeCannotBothCommit() throws Exception {
        // V58에서 둘 다 커밋돼 업무키가 어긋난 판 사슬이 남던 순서(PR #684 검토 재현)
        long v1 = freshBatch(false);
        try (var a = connect(); var b = connect()) {
            a.setAutoCommit(false);
            insertSuccessor(a, v1);
            b.setAutoCommit(false);
            assertThatThrownBy(() -> execute(b, "UPDATE fgc.statement_batch SET external_statement_no = 'OTHER-NO' "
                    + "WHERE statement_batch_id = " + v1)).hasMessageContaining("are immutable");
            b.rollback();
            execute(a, "UPDATE fgc.statement_batch SET status = 'SUPERSEDED' WHERE statement_batch_id = " + v1);
            a.commit();
        }
        try (var conn = connect()) {
            assertThat(scalar(conn, """
                    SELECT count(*) FROM fgc.statement_batch s JOIN fgc.statement_batch p
                      ON p.statement_batch_id = s.supersedes_statement_batch_id
                    WHERE (s.insurer_id, s.settlement_month, s.statement_type, COALESCE(s.external_statement_no, ''))
                          IS DISTINCT FROM
                          (p.insurer_id, p.settlement_month, p.statement_type, COALESCE(p.external_statement_no, ''))"""))
                    .isZero();
        }
    }

    @Test
    void sourceConfirmAndSupersessionSerializeOnTheBatchRow() throws Exception {
        long v1 = freshBatch(false);
        long first;
        long second;
        try (var conn = connect()) {
            first = insertDraftSource(conn, v1, "RACE-1");
            second = insertDraftSource(conn, v1, "RACE-2");
        }
        // 확정이 먼저 묶음 행을 잡으면 전환은 확정이 끝날 때까지 기다린다
        try (var confirmer = connect(); var replacer = connect()) {
            confirmer.setAutoCommit(false);
            confirm(confirmer, first);
            replacer.setAutoCommit(false);
            execute(replacer, "SET LOCAL lock_timeout = '300ms'");
            insertSuccessor(replacer, v1);
            assertThatThrownBy(() -> execute(replacer, "UPDATE fgc.statement_batch SET status = 'SUPERSEDED' "
                    + "WHERE statement_batch_id = " + v1))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(LOCK_TIMEOUT));
            replacer.rollback();
            confirmer.commit();
        }
        // 전환이 먼저 잡으면 확정이 기다리고, 전환이 커밋된 뒤에는 확정이 거절된다
        try (var replacer = connect(); var confirmer = connect()) {
            replacer.setAutoCommit(false);
            supersede(replacer, v1);
            confirmer.setAutoCommit(false);
            execute(confirmer, "SET LOCAL lock_timeout = '300ms'");
            assertThatThrownBy(() -> confirm(confirmer, second))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(LOCK_TIMEOUT));
            confirmer.rollback();
            replacer.commit();
            confirmer.setAutoCommit(true);
            assertThatThrownBy(() -> confirm(confirmer, second)).hasMessageContaining("cannot be confirmed");
        }
    }

    @Test
    void historyRowsMustReferenceTheRightPdfKind() throws Exception {
        try (var conn = connect()) {
            conn.setAutoCommit(false);
            try {
                insertCalculatedCase(conn, "KIND");
                long notice = insertNotice(conn, "KIND-NOTICE", "KIND-CASE", 1, 1);
                long confirmation = insertDocument(conn, "KIND-CONFIRM", "COMPARE_CONFIRMATION");
                assertRejected(conn, receiptSql(notice), "must reference a COMPARE_CONFIRMATION document");
                execute(conn, receiptSql(confirmation));
                assertRejected(conn, noticeSql(confirmation), "must reference a CLAWBACK_NOTICE document");
                execute(conn, noticeSql(notice));
            } finally {
                conn.rollback();
            }
        }
    }

    /** V75(#671): 통지서 판은 실제 계산판에서만, case별 고정 문서번호로, 판 번호를 건너뛰지 않고 쌓는다. */
    @Test
    void clawbackNoticeMustFollowItsCaseCalculationAndVersionSequence() throws Exception {
        try (var conn = connect()) {
            conn.setAutoCommit(false);
            try {
                insertCalculatedCase(conn, "SEQ-A");
                insertCalculatedCase(conn, "SEQ-B");
                assertRejected(conn, """
                        INSERT INTO fgc.generated_document(document_no, document_kind, source_entity_type, source_entity_id,
                                                           storage_key, content_hash, created_by)
                        SELECT 'SEQ-X', 'CLAWBACK_NOTICE', 'TEST', 1, 'key', 'hash', min(user_id) FROM fgc.app_user""",
                        "must reference a CLAWBACK_CASE calculation version");
                assertRejected(conn, noticeDocumentSql("SEQ-A-DOC", "SEQ-A-CASE", 2, 1), "references missing calculation");
                insertNotice(conn, "SEQ-A-DOC", "SEQ-A-CASE", 1, 1);
                assertRejected(conn, noticeDocumentSql("SEQ-A-DOC", "SEQ-A-CASE", 1, 3), "must be the next version");
                assertRejected(conn, noticeDocumentSql("SEQ-A-OTHER", "SEQ-A-CASE", 1, 1), "must stay fixed");
                assertRejected(conn, noticeDocumentSql("SEQ-A-DOC", "SEQ-B-CASE", 1, 2), "must stay fixed");
                insertNotice(conn, "SEQ-A-DOC", "SEQ-A-CASE", 1, 2);
            } finally {
                conn.rollback();
            }
        }
    }

    @Test
    void compareExplanationContentChangeMustIncrementVersion() throws Exception {
        try (var conn = connect()) {
            conn.setAutoCommit(false);
            try {
                execute(conn, """
                        INSERT INTO fgc.compare_product_group(group_code, insurance_sector, major_class, minor_class_name)
                        VALUES ('VER-GRP', 'LIFE', 'PROTECTION', 'test')""");
                execute(conn, """
                        INSERT INTO fgc.compare_explanation(grade_data_as_of, agent_id, compare_product_group_id,
                                                            recommended_offering_id, compared_offering_ids, selection_criteria,
                                                            recommendation_reason, fee_level_note)
                        SELECT DATE '2026-09-01', (SELECT min(agent_id) FROM fgc.agent),
                               (SELECT compare_product_group_id FROM fgc.compare_product_group WHERE group_code = 'VER-GRP'),
                               (SELECT min(product_offering_id) FROM fgc.product_offering), '[1]', 'c', 'r', 'f'""");
                assertRejected(conn, "UPDATE fgc.compare_explanation SET recommendation_reason = 'changed'",
                        "without incrementing version_no");
                assertRejected(conn, "UPDATE fgc.compare_explanation SET version_no = 3", "must stay 1 or become 2");
                execute(conn, "UPDATE fgc.compare_explanation SET recommendation_reason = 'changed', version_no = 2");
                // 최근 PDF 연결은 내용 변경이 아니므로 판을 올리지 않아도 된다
                long pdf = insertDocument(conn, "VER-PDF", "COMPARE_CONFIRMATION");
                execute(conn, "UPDATE fgc.compare_explanation SET generated_document_id = " + pdf);
                insertCalculatedCase(conn, "VER");
                long notice = insertNotice(conn, "VER-NOTICE", "VER-CASE", 1, 1);
                assertRejected(conn, "UPDATE fgc.compare_explanation SET generated_document_id = " + notice,
                        "must reference a COMPARE_CONFIRMATION document");
            } finally {
                conn.rollback();
            }
        }
    }

    /** 아직 판 사슬에 들지 않은 원본 묶음 하나. withConfirmedSources 면 CONFIRMED 원천이 있는 묶음을 고른다. */
    private long freshBatch(boolean withConfirmedSources) throws SQLException {
        try (var conn = connect()) {
            return scalar(conn, """
                    SELECT min(b.statement_batch_id) FROM fgc.statement_batch b
                    WHERE b.status = 'AVAILABLE' AND b.version_no = 1
                      AND NOT EXISTS (SELECT 1 FROM fgc.statement_batch s WHERE s.supersedes_statement_batch_id = b.statement_batch_id)
                      AND NOT EXISTS (SELECT 1 FROM fgc.commission_transaction t WHERE t.statement_batch_id = b.statement_batch_id
                                        AND t.source_business_key LIKE 'TEST-%%')
                      AND %s EXISTS (SELECT 1 FROM fgc.commission_transaction t WHERE t.statement_batch_id = b.statement_batch_id
                                       AND t.status = 'CONFIRMED')""".formatted(withConfirmedSources ? "" : "NOT"));
        }
    }

    private static void insertSuccessor(Connection conn, long v1) throws SQLException {
        execute(conn, """
                INSERT INTO fgc.statement_batch(insurer_id, settlement_month, statement_type, external_statement_no,
                                                received_on, source_ref, version_no, supersedes_statement_batch_id, data_origin)
                SELECT insurer_id, settlement_month, statement_type, external_statement_no, received_on, 'v2',
                       version_no + 1, statement_batch_id, 'AI_INTAKE'
                FROM fgc.statement_batch WHERE statement_batch_id = %d""".formatted(v1));
    }

    /** 운영정책서 제55조 5의 전환 순서 중 DB가 확인하는 부분: 새 판 → 이전 판 SUPERSEDED → 이전 원천 CANCELLED. */
    private static void supersede(Connection conn, long v1) throws SQLException {
        insertSuccessor(conn, v1);
        execute(conn, "UPDATE fgc.statement_batch SET status = 'SUPERSEDED' WHERE statement_batch_id = " + v1);
        execute(conn, "UPDATE fgc.commission_transaction SET status = 'CANCELLED' WHERE status = 'CONFIRMED' "
                + "AND statement_batch_id = " + v1);
    }

    /** 귀속행 없이 확정할 수 있도록 금액 0인 DRAFT 원천(귀속 합계 0 = 금액 0). */
    private static long insertDraftSource(Connection conn, long batchId, String key) throws SQLException {
        execute(conn, """
                INSERT INTO fgc.commission_transaction(statement_batch_id, payment_stage, source_type, source_business_key,
                                                       insurer_id, commission_item_id, settlement_month, amount, cashflow_type)
                SELECT statement_batch_id, 'INSURER_TO_GA', 'INSURER_STATEMENT', 'TEST-%s', insurer_id,
                       (SELECT min(commission_item_id) FROM fgc.commission_item), settlement_month, 0, 'PAYMENT'
                FROM fgc.statement_batch WHERE statement_batch_id = %d""".formatted(key, batchId));
        return scalar(conn, "SELECT commission_transaction_id FROM fgc.commission_transaction "
                + "WHERE source_business_key = 'TEST-" + key + "'");
    }

    private static void confirm(Connection conn, long transactionId) throws SQLException {
        execute(conn, "UPDATE fgc.commission_transaction SET status = 'CONFIRMED' WHERE commission_transaction_id = "
                + transactionId);
    }

    private static long insertDocument(Connection conn, String documentNo, String kind) throws SQLException {
        execute(conn, """
                INSERT INTO fgc.generated_document(document_no, document_kind, source_entity_type, source_entity_id,
                                                   storage_key, content_hash, created_by)
                SELECT '%s', '%s', 'TEST', 1, 'key', 'hash', min(user_id) FROM fgc.app_user""".formatted(documentNo, kind));
        return scalar(conn, "SELECT generated_document_id FROM fgc.generated_document WHERE document_no = '" + documentNo + "'");
    }

    private static String receiptSql(long documentId) {
        return """
                INSERT INTO fgc.compare_confirmation_receipt(generated_document_id, received_on, receipt_confirmed_by,
                                                             paper_storage_ref)
                SELECT %d, DATE '2026-09-01', min(user_id), 'ref' FROM fgc.app_user""".formatted(documentId);
    }

    /** 실효 사건 → 후보 → case({prefix}-CASE) → 계산판 1판. 통지서가 가리킬 실제 계산판을 만든다. */
    private static void insertCalculatedCase(Connection conn, String prefix) throws SQLException {
        execute(conn, """
                INSERT INTO fgc.insurance_contract(insurer_id, product_offering_id, contract_no, contract_date, agent_id,
                    organization_id, premium_per_cycle_amount, first_premium_amount, monthly_equivalent_first_premium,
                    payment_term_months)
                SELECT insurer_id, product_offering_id, '%1$s-CONTRACT', DATE '2026-01-01', agent_id, organization_id,
                       premium_per_cycle_amount, first_premium_amount, monthly_equivalent_first_premium, payment_term_months
                  FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1""".formatted(prefix));
        execute(conn, """
                INSERT INTO fgc.contract_status_event(contract_id, event_seq, new_status, effective_at, received_at,
                                                      source_system, source_event_key)
                SELECT contract_id, 1, 'LAPSED', TIMESTAMPTZ '2026-04-01T00:00:00+09:00', now(), 'TEST-671', '%1$s'
                  FROM fgc.insurance_contract WHERE contract_no = '%1$s-CONTRACT'""".formatted(prefix));
        execute(conn, """
                INSERT INTO fgc.clawback_candidate(contract_id, trigger_type, status_event_id, source_version, event_date)
                SELECT contract_id, 'STATUS_EVENT', contract_status_event_id, 'v', DATE '2026-04-01'
                  FROM fgc.contract_status_event WHERE source_system = 'TEST-671' AND source_event_key = '%1$s'"""
                .formatted(prefix));
        execute(conn, """
                INSERT INTO fgc.clawback_case(contract_id, policy_version_id, case_no, candidate_id)
                SELECT c.contract_id, (SELECT min(policy_version_id) FROM fgc.policy_version), '%1$s-CASE', c.candidate_id
                  FROM fgc.clawback_candidate c JOIN fgc.contract_status_event e
                    ON e.contract_status_event_id = c.status_event_id
                 WHERE e.source_system = 'TEST-671' AND e.source_event_key = '%1$s'""".formatted(prefix));
        execute(conn, """
                INSERT INTO fgc.clawback_calculation(clawback_case_id, candidate_id, version_no, source_version,
                    policy_version_id, calc_rule_version, elapsed_months, amount, reason, result_snapshot, calculated_by)
                SELECT clawback_case_id, candidate_id, 1, 'v', policy_version_id, 'claw-calc-v1', 3, 0, 'test', '{}',
                       (SELECT min(user_id) FROM fgc.app_user)
                  FROM fgc.clawback_case WHERE case_no = '%1$s-CASE'""".formatted(prefix));
    }

    private static long insertNotice(Connection conn, String documentNo, String caseNo, int calculationVersion,
                                     int versionNo) throws SQLException {
        execute(conn, noticeDocumentSql(documentNo, caseNo, calculationVersion, versionNo));
        return scalar(conn, "SELECT generated_document_id FROM fgc.generated_document WHERE document_no = '"
                + documentNo + "' AND version_no = " + versionNo);
    }

    private static String noticeDocumentSql(String documentNo, String caseNo, int calculationVersion, int versionNo) {
        return """
                INSERT INTO fgc.generated_document(document_no, version_no, document_kind, source_entity_type,
                                                   source_entity_id, source_version_no, storage_key, content_hash, created_by)
                SELECT '%s', %d, 'CLAWBACK_NOTICE', 'CLAWBACK_CASE', clawback_case_id, %d, 'key', 'hash',
                       (SELECT min(user_id) FROM fgc.app_user)
                  FROM fgc.clawback_case WHERE case_no = '%s'""".formatted(documentNo, versionNo, calculationVersion, caseNo);
    }

    private static String noticeSql(long documentId) {
        return """
                INSERT INTO fgc.clawback_notice_delivery(clawback_case_id, generated_document_id, recipient_agent_id,
                                                         notice_date, notice_kind, delivery_method, evidence_ref, registered_by)
                SELECT (SELECT clawback_case_id FROM fgc.clawback_case WHERE case_no = 'KIND-CASE'), %d,
                       (SELECT min(agent_id) FROM fgc.agent), DATE '2026-09-01', 'FIRST', 'POST', 'ref',
                       (SELECT min(user_id) FROM fgc.app_user)""".formatted(documentId);
    }

    private static void assertRejected(Connection conn, String sql, String expectedMessage) throws SQLException {
        var savepoint = conn.setSavepoint();
        try {
            assertThatThrownBy(() -> execute(conn, sql)).isInstanceOf(SQLException.class)
                    .hasMessageContaining(expectedMessage);
        } finally {
            conn.rollback(savepoint);
        }
    }

    private static String status(Connection conn, long batchId) throws SQLException {
        try (var st = conn.createStatement();
             var rs = st.executeQuery("SELECT status FROM fgc.statement_batch WHERE statement_batch_id = " + batchId)) {
            rs.next();
            return rs.getString(1);
        }
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
