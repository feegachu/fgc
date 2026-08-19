package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.validation.dto.MonthlyValidationJobParameters;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.mapper.ValidationRunMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #75 "상태 전이와 필수 감사 로그 저장은 동일 트랜잭션 경계에서 처리하여 감사 로그 누락
 * 상태가 발생하지 않도록 한다"를 실제 DB로 증명한다. mock으로는 진짜 롤백 여부를 증명할
 * 수 없다 — @Transactional이 실제로 두 매퍼 호출을 하나의 커밋 단위로 묶는지는 실제
 * PlatformTransactionManager와 실제 커넥션이 있어야만 확인된다.
 *
 * 감사로그(audit_log) INSERT를 일부러 실패시키는 방법: audit_log.user_id는
 * app_user(user_id)를 가리키는 FK다(V1__baseline_v2_1_2.sql). 존재하지 않는 triggeredBy를
 * 쓰면 진행상황(validation_run) UPDATE는 성공한 뒤, 그다음에 도는 감사로그 INSERT가
 * FK 위반으로 실패한다 — @Transactional이 진짜로 작동한다면 앞서 성공했던 UPDATE까지
 * 통째로 롤백되어야 한다.
 *
 * ★ 이 테스트 클래스에는 일부러 @Transactional을 안 붙인다. PostgreSQL은 제약 위반이 나면
 * 그 트랜잭션 전체가 "aborted" 상태가 되어(ValidationRunCreateServiceImpl의 같은 주석 참고)
 * 같은 트랜잭션 안에서는 뒤이은 조회(findById)조차 거부된다. lifecycleService.start()의
 * @Transactional(기본 REQUIRED)이 테스트 메서드를 감싸는 트랜잭션이 없는 상태에서 불리면
 * "이 호출 하나만의" 새 트랜잭션을 열고 실패 시 그 트랜잭션만 깔끔하게 롤백·종료하므로,
 * 그 뒤에 오는 findById는 완전히 새로운(정상 상태의) 트랜잭션에서 실행된다. 대신 롤백에
 * 기댈 수 없으니 @AfterEach에서 직접 지운다.
 */
@SpringBootTest
class ValidationRunBatchLifecycleServiceImplIntegrationTest {

    // 존재하지 않는 app_user.user_id — audit_log INSERT를 FK 위반으로 실패시키기 위한 값.
    // demo 시드가 만드는 user_id는 한 자릿수라 이 값과 절대 겹치지 않는다.
    private static final long NON_EXISTENT_USER_ID = 999_999_999L;

    // cleanUp()이 이 월을 통째로 지우므로(@Transactional 없이 실제 커밋한다) 이 클래스 전용 월이어야
    // 한다 — 다른 테스트와 공유하는 월을 쓰면 남의 행까지 지우려다 FK 위반으로 DELETE가 통째로
    // 실패하고, 그 뒤로 남은 행이 다른 테스트를 무너뜨린다(2099-01에서 실제로 발생, bee042b4).
    private static final LocalDate TEST_MONTH = LocalDate.of(2093, 1, 1);

    @Autowired
    private ValidationRunBatchLifecycleService lifecycleService;

    @Autowired
    private ValidationRunMapper validationRunMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM fgc.validation_run WHERE validation_month = ?", TEST_MONTH);
    }

    private Long insertCreatedRun(LocalDate month, int runNo) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, status)
                VALUES (?, ?, 'CREATED')
                RETURNING validation_run_id
                """, Long.class, month, runNo);
    }

    @Test
    // start()가 progressService.startRunning()까지는 성공시키고 auditService.recordStarted()
    // 에서 FK 위반으로 실패하면, validation_run은 RUNNING으로 남지 않고 CREATED 그대로여야
    // 한다 — "상태는 바뀌었는데 감사로그가 없는" 상태가 남으면 안 된다는 요구사항 그대로.
    void startRollsBackProgressUpdateWhenAuditLogInsertFails() {
        Long id = insertCreatedRun(TEST_MONTH, 1);
        MonthlyValidationJobParameters badParameters = new MonthlyValidationJobParameters(
                TEST_MONTH, 1L, ValidationRunType.MONTHLY, NON_EXISTENT_USER_ID, "request-1");

        assertThatThrownBy(() -> lifecycleService.start(id, badParameters))
                .isInstanceOf(DataIntegrityViolationException.class);

        // start()가 열었던 트랜잭션은 이미 롤백되고 끝난 뒤라, 이 조회는 완전히 새 트랜잭션에서
        // 실행된다 — 클래스 주석 참고. 여기서 CREATED가 보인다는 것 자체가 "progressService의
        // UPDATE까지 같이 롤백됐다"는 증거다(안 그랬다면 RUNNING으로 보여야 한다).
        ValidationRunRow row = validationRunMapper.findById(id);
        assertThat(row.getStatus()).isEqualTo("CREATED");
        assertThat(row.getCurrentStep()).isZero();
        assertThat(row.getStartedAt()).isNull();
    }
}
