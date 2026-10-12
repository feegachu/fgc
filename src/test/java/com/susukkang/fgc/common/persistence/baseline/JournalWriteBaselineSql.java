package com.susukkang.fgc.common.persistence.baseline;

import static com.susukkang.fgc.common.persistence.baseline.JdbcBaseline.*;

/** 전환 전 SQL 비교 기준. 운영 저장소와 공유하지 않는 JDBC 테스트 픽스처. */
final class JournalWriteBaselineSql {
    static Statement statement(String method, Object input) {
        return switch (method) {
            case "insert" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "journalNo")));
                parameters.addValue("p1", parameter(value(input, "journalDate")));
                parameters.addValue("p2", parameter(value(input, "journalType")));
                parameters.addValue("p3", parameter(value(input, "sourceEntityType")));
                parameters.addValue("p4", parameter(value(input, "sourceEntityId")));
                parameters.addValue("p5", parameter(value(input, "revisionNo")));
                parameters.addValue("p6", parameter(value(input, "validationRunId")));
                parameters.addValue("p7", parameter(value(input, "contractId")));
                parameters.addValue("p8", parameter(value(input, "policyVersionId")));
                parameters.addValue("p9", parameter(value(input, "reversalOfId")));
                parameters.addValue("p10", parameter(value(input, "correctionGroupKey")));
                parameters.addValue("p11", parameter(value(input, "description")));
                parameters.addValue("p12", parameter(value(input, "createdBy")));
                sql.append("""
                    INSERT INTO fgc.journal_header
                        (journal_no, journal_date, journal_type, source_entity_type, source_entity_id,
                         revision_no, validation_run_id, contract_id, policy_version_id,
                         reversal_of_id, correction_group_key, description, created_by)
                    VALUES
                        (:p0, :p1, :p2, :p3, :p4,
                         :p5, :p6, :p7, :p8,
                         :p9, :p10, :p11, :p12)
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "journalHeaderId", "journal_header_id");
            }
            case "insertLine" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "journalHeaderId")));
                parameters.addValue("p1", parameter(value(input, "lineNo")));
                parameters.addValue("p2", parameter(value(input, "journalAccountId")));
                parameters.addValue("p3", parameter(value(input, "debitAmount")));
                parameters.addValue("p4", parameter(value(input, "creditAmount")));
                parameters.addValue("p5", parameter(value(input, "contractId")));
                parameters.addValue("p6", parameter(value(input, "agentId")));
                parameters.addValue("p7", parameter(value(input, "paymentStage")));
                parameters.addValue("p8", parameter(value(input, "commissionItemId")));
                parameters.addValue("p9", parameter(value(input, "memo")));
                sql.append("""
                    INSERT INTO fgc.journal_line
                        (journal_header_id, line_no, journal_account_id, debit_amount, credit_amount,
                         contract_id, agent_id, payment_stage, commission_item_id, memo)
                    VALUES
                        (:p0, :p1, :p2, :p3, :p4,
                         :p5, :p6, :p7, :p8, :p9)
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "journalLineId", "journal_line_id");
            }
            case "findNextJournalSeq" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "year")));
                parameters.addValue("p1", parameter(value(input, "month")));
                sql.append("""
                    SELECT COALESCE(MAX(CAST(SUBSTRING(journal_no FROM 'JV-\\d{4}-\\d{2}-(\\d+)$') AS int)), 0) + 1
                    FROM fgc.journal_header
                    WHERE journal_no LIKE 'JV-' || :p0 || '-' || :p1 || '-%'
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(Integer.class), "", "");
            }
            case "findBySourceKey" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "journalType")));
                parameters.addValue("p1", parameter(value(input, "sourceEntityType")));
                parameters.addValue("p2", parameter(value(input, "sourceEntityId")));
                parameters.addValue("p3", parameter(value(input, "revisionNo")));
                sql.append("""
                    SELECT journal_header_id, journal_no, journal_date, journal_type, source_entity_type,
                           source_entity_id, revision_no, validation_run_id, contract_id, policy_version_id,
                           status, description, created_by, posted_by, posted_at, created_at
                    FROM fgc.journal_header
                    WHERE journal_type = :p0 AND source_entity_type = :p1
                    AND source_entity_id = :p2 AND revision_no = :p3
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.journal.dto.JournalHeaderRow.class), "", "");
            }
            case "existsPostedForSource" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "journalType")));
                parameters.addValue("p1", parameter(value(input, "sourceEntityType")));
                parameters.addValue("p2", parameter(value(input, "sourceEntityId")));
                sql.append("""
                    SELECT EXISTS (
                        SELECT 1 FROM fgc.journal_header
                        WHERE journal_type = :p0 AND source_entity_type = :p1
                        AND source_entity_id = :p2 AND status = 'POSTED'
                    )
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(Boolean.class), "", "");
            }
            case "markPosted" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "postedBy")));
                parameters.addValue("p1", parameter(value(input, "journalHeaderId")));
                sql.append("""
                    UPDATE fgc.journal_header
                    SET status = 'POSTED', posted_by = :p0
                    WHERE journal_header_id = :p1 AND status = 'DRAFT'
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "", "");
            }
            default -> throw new IllegalArgumentException("Unknown baseline statement: " + method);
        };
    }
}
