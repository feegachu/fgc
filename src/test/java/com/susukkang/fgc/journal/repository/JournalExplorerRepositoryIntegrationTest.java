package com.susukkang.fgc.journal.repository;

import com.susukkang.fgc.journal.dto.JournalExplorer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class JournalExplorerRepositoryIntegrationTest {
    @Autowired JournalExplorerRepository repository;
    @Autowired JdbcTemplate jdbc;
    private final LocalDate date = LocalDate.of(2044, 6, 1);
    private long header(String no, Long contractId) {
        return jdbc.queryForObject("""
            INSERT INTO fgc.journal_header (journal_no,journal_date,journal_type,source_entity_type,source_entity_id,contract_id)
            VALUES (?,?,'ADJUSTMENT','TEST',?,?) RETURNING journal_header_id
            """, Long.class, no, date, no, contractId);
    }
    private void line(long header, int no, String account, Long contractId, int debit, int credit) {
        jdbc.update("""
            INSERT INTO fgc.journal_line (journal_header_id,line_no,journal_account_id,contract_id,debit_amount,credit_amount)
            SELECT ?,?,journal_account_id,?,?,? FROM fgc.journal_account WHERE account_code=?
            """, header,no,contractId,debit,credit,account);
    }
    private JournalExplorer.Filter filter(String account) {
        return new JournalExplorer.Filter(date,date,null,account,null,null);
    }
    @Test void linksLineOnlyContractsAndPreservesBalancedJournalWhenFilteringAnAccount() {
        long contract = jdbc.queryForObject("SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1", Long.class);
        long id = header("EXP-TEST-LINKED", null);
        line(id,1,"EXPECTED_RECEIVABLE",contract,50000,0);
        line(id,2,"EXPECTED_INCOME",null,0,50000);
        var result = repository.explore(filter("EXPECTED_RECEIVABLE"),contract,false,null,1,1,20);
        assertThat(result.contracts().content()).hasSize(1);
        assertThat(result.contracts().content().getFirst().contractId()).isEqualTo(contract);
        assertThat(result.journals().content()).hasSize(1);
        var entry = result.journals().content().getFirst();
        assertThat(entry.lines()).hasSize(2);
        assertThat(entry.debitTotal()).isEqualByComparingTo(new BigDecimal("50000"));
        assertThat(entry.creditTotal()).isEqualByComparingTo(entry.debitTotal());
        assertThat(repository.explore(filter(null),contract,false,id,1,1,20).journals().content()).hasSize(1);
    }
    @Test void keepsCommonJournalsAndPaginatesContractsSeparately() {
        var contracts = jdbc.queryForList("SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 2",Long.class);
        header("EXP-TEST-A",contracts.get(0)); header("EXP-TEST-B",contracts.get(1));
        long common = header("EXP-TEST-COMMON",null);
        var first = repository.explore(filter(null),null,false,null,1,1,1);
        var next = repository.explore(filter(null),null,false,null,2,1,1);
        assertThat(first.contracts().totalElements()).isEqualTo(3);
        assertThat(first.contracts().content().getFirst().contractId()).isNotEqualTo(next.contracts().content().getFirst().contractId());
        var commonResult = repository.explore(filter(null),0L,false,null,1,1,20);
        assertThat(commonResult.journals().content()).extracting(JournalExplorer.Entry::journalHeaderId).containsExactly(common);
        assertThat(repository.explore(filter(null),null,true,null,1,1,20).journals().totalElements()).isEqualTo(3);
    }
    @Test void sharedJournalKeepsWholeEntryPerContractButCountsOnceInAllTotals() {
        var contracts = jdbc.queryForList("SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 2", Long.class);
        long id = header("EXP-TEST-SHARED", null);
        line(id,1,"EXPECTED_RECEIVABLE",contracts.get(0),30000,0);
        line(id,2,"EXPECTED_RECEIVABLE",contracts.get(1),20000,0);
        line(id,3,"EXPECTED_INCOME",null,0,50000);
        for (Long contract : contracts) {
            var selected = repository.explore(filter(null),contract,false,null,1,1,20);
            assertThat(selected.totals().debitTotal()).isEqualByComparingTo("50000");
            assertThat(selected.totals().creditTotal()).isEqualByComparingTo("50000");
            assertThat(selected.journals().content().getFirst().lines()).hasSize(3);
        }
        var all = repository.explore(filter(null),null,true,null,1,1,20);
        assertThat(all.contracts().totalElements()).isEqualTo(2);
        assertThat(all.journals().totalElements()).isEqualTo(1);
        assertThat(all.totals().debitTotal()).isEqualByComparingTo("50000");
    }
}
