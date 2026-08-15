package com.susukkang.fgc.exceptioncase.service;

import com.susukkang.fgc.common.code.ExceptionActionType;
import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.common.web.RequestIdContext;
import com.susukkang.fgc.exceptioncase.dto.ExceptionActionRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** IF-API-44 상태 변경·이력·감사로그가 같은 트랜잭션에서 저장되는지 검증한다. */
@SpringBootTest
@Transactional
class ExceptionCaseActionServiceIntegrationTest {

    @Autowired
    private ExceptionCaseService exceptionCaseService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearRequestId() {
        RequestIdContext.clear();
    }

    @Test
    void 검토시작과_해결은_상태와_순번과_감사로그를_함께_남긴다() {
        Long userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM fgc.app_user ORDER BY user_id LIMIT 1", Long.class);
        String loginId = jdbcTemplate.queryForObject(
                "SELECT login_id FROM fgc.app_user WHERE user_id = ?", String.class, userId);
        Long exceptionCaseId = insertNewCase();
        RequestIdContext.set("it-exception-action");

        var started = exceptionCaseService.action(
                exceptionCaseId,
                new ExceptionActionRequest(ExceptionActionType.START_REVIEW, "검토를 시작합니다.", null),
                userId,
                loginId);
        var resolved = exceptionCaseService.action(
                exceptionCaseId,
                new ExceptionActionRequest(ExceptionActionType.RESOLVE, "근거 확인 후 해결했습니다.", "DOC-001"),
                userId,
                loginId);

        assertThat(started.actionSeq()).isEqualTo(1);
        assertThat(started.fromStatus()).isEqualTo(ExceptionStatus.NEW);
        assertThat(started.toStatus()).isEqualTo(ExceptionStatus.IN_REVIEW);
        assertThat(resolved.actionSeq()).isEqualTo(2);
        assertThat(resolved.toStatus()).isEqualTo(ExceptionStatus.RESOLVED);
        assertThat(resolved.actionByLoginId()).isEqualTo(loginId);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM fgc.exception_case WHERE exception_case_id = ?",
                String.class,
                exceptionCaseId);
        assertThat(status).isEqualTo("RESOLVED");

        Integer actionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.exception_action WHERE exception_case_id = ?",
                Integer.class,
                exceptionCaseId);
        assertThat(actionCount).isEqualTo(2);

        Integer auditCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM fgc.audit_log
                 WHERE entity_type = 'EXCEPTION_CASE'
                   AND entity_id = ?
                   AND action_code = 'EXCEPTION_ACTION'
                """, Integer.class, String.valueOf(exceptionCaseId));
        assertThat(auditCount).isEqualTo(2);
    }

    private Long insertNewCase() {
        String suffix = UUID.randomUUID().toString();
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.exception_case (
                    exception_key, exception_type, severity, status,
                    source_entity_type, source_entity_id, title
                ) VALUES (?, 'DATA_QUALITY', 'WARNING', 'NEW', 'IT', ?, '처리 테스트')
                RETURNING exception_case_id
                """, Long.class, "IT-ACTION:" + suffix, suffix);
    }
}
