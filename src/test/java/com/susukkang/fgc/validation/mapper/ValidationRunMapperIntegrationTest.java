package com.susukkang.fgc.validation.mapper;

import com.susukkang.fgc.validation.dto.ValidationRunRow;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

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

    // guard_run_lifecycle(V7)이 INSERT 시 status='CREATED'만 허용
    private Long insertCreatedRun(LocalDate month, int runNo) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, status)
                VALUES (?, ?, 'CREATED')
                RETURNING validation_run_id
                """, Long.class, month, runNo);
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
}
