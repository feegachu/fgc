package com.susukkang.fgc.validation.mapper;


import com.susukkang.fgc.validation.dto.ValidationRunInsertRow;
import com.susukkang.fgc.validation.dto.ValidationRunListRow;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.Disabled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/**
 * ValidationRunMapper를 로컬 db로 검증
 * 특히 updateStatusIfCurrent의 "조건부" 동작(이미 상태가 바뀐 뒤에는 0건이어야 함)은
 * mock으로는 증명이 안 되고 실제 UPDATE ... WHERE status = ? 가 정말 그렇게 동작하는지 확인해야함
 */
@SpringBootTest
@Transactional
class ValidationRunMapperIntegrationTest {

    @Autowired
    private ValidationRunMapper validationRunMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;


    // guard_run_lifecycle(V7)이 INSERT 시 status='CREATED'만 허용. run_type은 안 넣으면
    // DB 기본값(MONTHLY)이 채워진다.

    private Long insertCreatedRun(LocalDate month, int runNo) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, status)
                VALUES (?, ?, 'CREATED')
                RETURNING validation_run_id
                """, Long.class, month, runNo);
    }


    private Long insertCreatedRun(LocalDate month, int runNo, String runType) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type, status)
                VALUES (?, ?, ?, 'CREATED')
                RETURNING validation_run_id
                """, Long.class, month, runNo, runType);
    }

    @Test
    // findById로 방금 넣은 CREATED 행이 그대로 읽히는지
    void findByIdReturnsInsertedRow() {
        Long id = insertCreatedRun(LocalDate.of(2026, 8, 1), 1);

        ValidationRunRow row = validationRunMapper.findById(id);

        assertThat(row).isNotNull();
        assertThat(row.getValidationRunId()).isEqualTo(id);
        assertThat(row.getStatus()).isEqualTo("CREATED");
        assertThat(row.getValidationMonth()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(row.getRunNo()).isEqualTo(1);
    }

    @Test
    // updateStatusIfCurrent(id, \"CREATED\", \"RUNNING\")가 1을 반환하고 실제 status도 바뀌는지"
    void updateStatusIfCurrentSucceedsWhenExpectedStatusMatches() {
        Long id = insertCreatedRun(LocalDate.of(2026, 8, 1), 2);

        int affected = validationRunMapper.updateStatusIfCurrent(id, "CREATED", "RUNNING");

        assertThat(affected).isEqualTo(1);
        assertThat(validationRunMapper.findById(id).getStatus()).isEqualTo("RUNNING");
    }

    @Test
    // 실제 status는 CREATED인데 expectedStatus를 RUNNING으로 주면 0을 반환하고 status가 그대로인지
    void updateStatusIfCurrentReturnsZeroWhenExpectedStatusIsStale() {
        Long id = insertCreatedRun(LocalDate.of(2026, 8, 1), 3);

        int affected = validationRunMapper.updateStatusIfCurrent(id, "RUNNING", "COMPLETED");

        assertThat(affected).isZero();
        assertThat(validationRunMapper.findById(id).getStatus()).isEqualTo("CREATED");
    }

    @Test
    // 존재하지 않는 validationRunId로 updateStatusIfCurrent를 호출하면 역시 0을 반환하는지
    void updateStatusIfCurrentReturnsZeroWhenRunDoesNotExist() {
        int affected = validationRunMapper.updateStatusIfCurrent(999_999_999L, "CREATED", "RUNNING");

        assertThat(affected).isZero();
    }

    @Test
    // 해당 월에 행이 하나도 없으면 findNextRunNo가 1을 반환하는지
    void findNextRunNoReturnsOneWhenNoRunsExistForMonth() {
        Integer nextRunNo = validationRunMapper.findNextRunNo(LocalDate.of(2026, 9, 1));

        assertThat(nextRunNo).isEqualTo(1);
    }

    @Test
    // run_no 1,2가 이미 있으면 findNextRunNo가 3을 반환하는지 둘 다 MANUAL_CONTRACT로 넣음
    void findNextRunNoReturnsMaxPlusOne() {
        LocalDate month = LocalDate.of(2026, 9, 1);
        insertCreatedRun(month, 1, "MANUAL_CONTRACT");
        insertCreatedRun(month, 2, "MANUAL_CONTRACT");

        Integer nextRunNo = validationRunMapper.findNextRunNo(month);

        assertThat(nextRunNo).isEqualTo(3);
    }

    @Test
    // MONTHLY + CREATED/RUNNING 실행이 있으면 existsActiveMonthlyRun이 true인지
    void existsActiveMonthlyRunReturnsTrueForActiveMonthlyRun() {
        LocalDate month = LocalDate.of(2026, 9, 1);
        insertCreatedRun(month, 1);

        boolean exists = validationRunMapper.existsActiveMonthlyRun(month);

        assertThat(exists).isTrue();
    }

    @Test
    // 같은 달에 COMPLETED MONTHLY 실행만 있으면 false인지
    void existsActiveMonthlyRunReturnsFalseWhenNoActiveRun() {
        LocalDate month = LocalDate.of(2026, 9, 1);
        Long id = insertCreatedRun(month, 1);
        validationRunMapper.updateStatusIfCurrent(id, "CREATED", "RUNNING");
        jdbcTemplate.update(
                "UPDATE fgc.validation_run SET status = 'COMPLETED', current_step = 8 WHERE validation_run_id = ?",
                id);

        boolean exists = validationRunMapper.existsActiveMonthlyRun(month);

        assertThat(exists).isFalse();
    }

    @Test
    // MANUAL_CONTRACT 실행은 활성(CREATED)이어도 existsActiveMonthlyRun에 안 잡히는지
    void existsActiveMonthlyRunIgnoresNonMonthlyRunType() {
        LocalDate month = LocalDate.of(2026, 9, 1);
        insertCreatedRun(month, 1, "MANUAL_CONTRACT");

        boolean exists = validationRunMapper.existsActiveMonthlyRun(month);

        assertThat(exists).isFalse();
    }

    @Test
    // insert 후 row.validationRunId가 채워지고, status/current_step은 DB 기본값(CREATED/0)인지
    void insertGeneratesIdAndAppliesDefaults() {
        ValidationRunInsertRow row = ValidationRunInsertRow.builder()
                .validationMonth(LocalDate.of(2026, 9, 1))
                .runNo(1)
                .runType("MONTHLY")
                .build();

        validationRunMapper.insert(row);

        assertThat(row.getValidationRunId()).isNotNull();

        ValidationRunRow found = validationRunMapper.findById(row.getValidationRunId());
        assertThat(found.getStatus()).isEqualTo("CREATED");

        Integer currentStep = jdbcTemplate.queryForObject(
                "SELECT current_step FROM fgc.validation_run WHERE validation_run_id = ?",
                Integer.class, row.getValidationRunId());
        assertThat(currentStep).isZero();
    }

    @Test
    // 같은 달·같은 run_no로 두 번 insert하면 uq_validation_run 위반(DataIntegrityViolationException)인지
    void insertFailsOnDuplicateRunNoInSameMonth() {
        LocalDate month = LocalDate.of(2026, 9, 1);
        insertCreatedRun(month, 1);

        ValidationRunInsertRow duplicate = ValidationRunInsertRow.builder()
                .validationMonth(month)
                .runNo(1)
                .runType("MANUAL_CONTRACT")
                .build();

        assertThatThrownBy(() -> validationRunMapper.insert(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── #41 목록 조회(search/count) ──────────────────────────────────────────
    // ValidationRunMapper.xml에 search/count <select>를 작성하기 전까지는 여기서부터가 전부
    // BindingException(Invalid bound statement)으로 실패한다 — 정상이다.

    @Test
    // month로 걸면 그 달 실행만 나오는지
    void searchFiltersByMonth() {
        insertCreatedRun(LocalDate.of(2026, 9, 1), 1);
        insertCreatedRun(LocalDate.of(2026, 10, 1), 1);

        List<ValidationRunListRow> rows =
                validationRunMapper.search(LocalDate.of(2026, 9, 1), null, 0, 20);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getValidationMonth()).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    @Test
    // status로 걸면 그 상태 실행만 나오는지
    void searchFiltersByStatus() {
        LocalDate month = LocalDate.of(2026, 9, 1);
        Long runningId = insertCreatedRun(month, 1);
        validationRunMapper.updateStatusIfCurrent(runningId, "CREATED", "RUNNING");
        // uq_validation_run_active_month는 월당 활성(CREATED/RUNNING) MONTHLY 실행을 1건만
        // 허용한다 — 위에서 이미 RUNNING 하나를 썼으니 두 번째는 MANUAL_CONTRACT로 넣는다.
        insertCreatedRun(month, 2, "MANUAL_CONTRACT");

        List<ValidationRunListRow> rows = validationRunMapper.search(month, "RUNNING", 0, 20);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getValidationRunId()).isEqualTo(runningId);
        assertThat(rows.get(0).getStatus()).isEqualTo("RUNNING");
    }

    @Test
    // 조건에 맞는 실행이 없으면 빈 리스트(에러 아님)
    void searchReturnsEmptyListWhenNoMatch() {
        List<ValidationRunListRow> rows = validationRunMapper.search(LocalDate.of(2099, 1, 1), null, 0, 20);

        assertThat(rows).isEmpty();
    }

    @Test
    // month/status 둘 다 null이면(조건 없음) 전체가 나오는지, count도 search 건수와 일치하는지
    void searchAndCountReturnAllRowsWhenNoFilterGiven() {
        LocalDate month = LocalDate.of(2026, 9, 1);
        insertCreatedRun(month, 1);
        // uq_validation_run_active_month 때문에 같은 달 두 번째 MONTHLY 활성 실행은 못 넣는다.
        insertCreatedRun(month, 2, "MANUAL_CONTRACT");

        List<ValidationRunListRow> rows = validationRunMapper.search(null, null, 0, 20);
        long total = validationRunMapper.count(null, null);

        assertThat(rows).hasSize(2);
        assertThat(total).isEqualTo(2);
    }

    @Test
    // limit/offset이 실제로 페이징되는지 — run_no 1,2,3 중 offset=1, limit=1이면 1건만
    void searchRespectsOffsetAndLimit() {
        LocalDate month = LocalDate.of(2026, 9, 1);
        insertCreatedRun(month, 1);
        // uq_validation_run_active_month 때문에 같은 달 두 번째부터는 MANUAL_CONTRACT로 넣는다.
        insertCreatedRun(month, 2, "MANUAL_CONTRACT");
        insertCreatedRun(month, 3, "MANUAL_CONTRACT");

        List<ValidationRunListRow> rows = validationRunMapper.search(month, null, 1, 1);

        assertThat(rows).hasSize(1);
    }

}
