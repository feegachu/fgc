package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.journal.dto.JournalAccountRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 활성 계정과목의 정렬과 엔티티→화면 DTO 변환을 실제 DB에서 확인한다. */
@SpringBootTest
@Transactional
class JournalAccountCatalogServiceIntegrationTest {

    @Autowired
    private JournalAccountCatalogService service;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void returnsOnlyActiveAccountsInCodeOrderWithAllDtoFields() {
        String prefix = "JAC_" + UUID.randomUUID().toString().replace("-", "");
        String debitCode = prefix + "_A";
        String creditCode = prefix + "_B";
        String inactiveCode = prefix + "_C";
        // 삽입 순서와 코드 순서를 다르게 만들어 Repository 정렬을 확인한다.
        Long creditId = insertAccount(creditCode, "조회 검증 대변", "CREDIT", true);
        Long debitId = insertAccount(debitCode, "조회 검증 차변", "DEBIT", true);
        Long inactiveId = insertAccount(inactiveCode, "조회 제외 계정", "DEBIT", false);

        List<JournalAccountRow> allRows = service.findAllActive();
        List<JournalAccountRow> rows = allRows.stream()
                .filter(row -> row.getAccountCode().startsWith(prefix))
                .toList();

        assertThat(allRows).allSatisfy(row -> assertThat(row.getActiveYn()).isTrue());
        assertThat(allRows).extracting(JournalAccountRow::getJournalAccountId).doesNotContain(inactiveId);
        assertThat(rows).extracting(JournalAccountRow::getAccountCode).containsExactly(debitCode, creditCode);
        assertAccount(rows.get(0), debitId, debitCode, "조회 검증 차변", "DEBIT");
        assertAccount(rows.get(1), creditId, creditCode, "조회 검증 대변", "CREDIT");
    }

    private Long insertAccount(String code, String name, String normalBalance, boolean active) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.journal_account (account_code, account_name, normal_balance, active_yn)
                VALUES (?, ?, ?, ?) RETURNING journal_account_id
                """, Long.class, code, name, normalBalance, active);
    }

    private void assertAccount(JournalAccountRow row, Long id, String code, String name, String normalBalance) {
        assertThat(row.getJournalAccountId()).isEqualTo(id);
        assertThat(row.getAccountCode()).isEqualTo(code);
        assertThat(row.getAccountName()).isEqualTo(name);
        assertThat(row.getNormalBalance()).isEqualTo(normalBalance);
        assertThat(row.getActiveYn()).isTrue();
    }
}
