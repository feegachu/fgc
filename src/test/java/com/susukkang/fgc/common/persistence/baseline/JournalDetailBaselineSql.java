package com.susukkang.fgc.common.persistence.baseline;

import static com.susukkang.fgc.common.persistence.baseline.JdbcBaseline.*;

/** 전환 전 SQL 비교 기준. 운영 저장소와 공유하지 않는 JDBC 테스트 픽스처. */
final class JournalDetailBaselineSql {
    static Statement statement(String method, Object input) {
        return switch (method) {
            case "findHeaderById" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "journalHeaderId")));
                sql.append("""
                    SELECT h.journal_header_id, h.journal_no, h.journal_date, h.journal_type,
                           h.source_entity_type, h.source_entity_id, h.revision_no,
                           h.contract_id, c.contract_no AS contractNo,
                           h.validation_run_id, h.policy_version_id,
                           COALESCE(h.correction_group_key, correction_group.correction_group_key) AS correctionGroupKey,
                           h.status, h.description,
                           creator.login_id AS createdBy, h.created_at AS createdAt,
                           poster.login_id AS postedBy, h.posted_at AS postedAt,
                           h.reversal_of_id AS reversalOfId, rev.journal_no AS reversalOfJournalNo,
                           successor.journal_header_id AS reversedByJournalHeaderId,
                           successor.journal_no AS reversedByJournalNo,
                           repost.journal_header_id AS repostedJournalHeaderId,
                           repost.journal_no AS repostedJournalNo
                    FROM fgc.journal_header h
                    LEFT JOIN fgc.insurance_contract c ON c.contract_id = h.contract_id
                    LEFT JOIN fgc.app_user creator ON creator.user_id = h.created_by
                    LEFT JOIN fgc.app_user poster ON poster.user_id = h.posted_by
                    LEFT JOIN fgc.journal_header rev ON rev.journal_header_id = h.reversal_of_id
                    LEFT JOIN fgc.journal_header successor ON successor.reversal_of_id = h.journal_header_id




                    LEFT JOIN fgc.journal_correction_group correction_group
                           ON correction_group.original_journal_header_id = h.journal_header_id
                           OR correction_group.correction_group_key = h.correction_group_key
                    LEFT JOIN fgc.journal_header repost
                           ON repost.correction_group_key = correction_group.correction_group_key
                          AND repost.journal_type != 'REVERSAL'
                    WHERE h.journal_header_id = :p0
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.journal.dto.JournalDetailHeaderRow.class), "", "");
            }
            case "findLinesByHeaderId" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "journalHeaderId")));
                sql.append("""
                    SELECT l.line_no, a.account_code, a.account_name, a.normal_balance,
                           l.debit_amount, l.credit_amount, l.contract_id,
                           l.agent_id, ag.agent_name, l.payment_stage,
                           l.commission_item_id, ci.item_name AS commissionItemName, l.memo
                    FROM fgc.journal_line l
                    JOIN fgc.journal_account a ON a.journal_account_id = l.journal_account_id
                    LEFT JOIN fgc.agent ag ON ag.agent_id = l.agent_id
                    LEFT JOIN fgc.commission_item ci ON ci.commission_item_id = l.commission_item_id
                    WHERE l.journal_header_id = :p0
                    ORDER BY l.line_no
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.journal.dto.JournalDetailLineRow.class), "", "");
            }
            default -> throw new IllegalArgumentException("Unknown baseline statement: " + method);
        };
    }
}
