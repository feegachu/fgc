package com.susukkang.fgc.validation.mapper;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * ValidationRunMapper를 실제 DB(로컬 fgc-db)로 검증한다.
 * 특히 updateStatusIfCurrent의 "조건부" 동작 — 이미 상태가 바뀐 뒤에는 0건이어야 함 — 은
 * mock으로는 증명이 안 되고 실제 UPDATE ... WHERE status = ? 가 정말 그렇게 동작하는지
 * DB에 대고 확인해야 의미가 있다.
 */
@SpringBootTest
@Transactional
class ValidationRunMapperIntegrationTest {

    @Autowired
    private ValidationRunMapper validationRunMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // guard_run_lifecycle(V7)이 INSERT 시 status='CREATED'만 허용한다.
    // DashboardServiceIntegrationTest#insertValidationRun과 같은 제약이니 그쪽 주석도 참고할 것.
    private Long insertCreatedRun(LocalDate month, int runNo) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, status)
                VALUES (?, ?, 'CREATED')
                RETURNING validation_run_id
                """, Long.class, month, runNo);
    }

    @Test
    @Disabled("TODO(FUN-041): findById로 방금 넣은 CREATED 행이 그대로 읽히는지")
    void findByIdReturnsInsertedRow() {
    }

    @Test
    @Disabled("TODO(FUN-041): updateStatusIfCurrent(id, \"CREATED\", \"RUNNING\")가 1을 반환하고 실제 status도 바뀌는지")
    void updateStatusIfCurrentSucceedsWhenExpectedStatusMatches() {
    }

    @Test
    @Disabled("TODO(FUN-041): 실제 status는 CREATED인데 expectedStatus를 RUNNING으로 주면 0을 반환하고 "
            + "status가 그대로인지 — 이게 '상태 충돌'을 코드로 흉내낸 것")
    void updateStatusIfCurrentReturnsZeroWhenExpectedStatusIsStale() {
    }

    @Test
    @Disabled("TODO(FUN-041): 존재하지 않는 validationRunId로 updateStatusIfCurrent를 호출하면 "
            + "역시 0을 반환하는지 — Service가 findById와 조합해 '미존재'와 '충돌'을 구분해야 하는 이유")
    void updateStatusIfCurrentReturnsZeroWhenRunDoesNotExist() {
    }
}
