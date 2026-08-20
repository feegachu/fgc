package com.susukkang.fgc.exceptioncase.service;

import com.susukkang.fgc.common.code.ExceptionActionType;
import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.RequestIdContext;
import com.susukkang.fgc.exceptioncase.dto.ExceptionActionRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                new ExceptionActionRequest(
                        ExceptionActionType.START_REVIEW, "검토를 시작합니다.", "DOC-START-001"),
                userId,
                loginId);
        var resolved = exceptionCaseService.action(
                exceptionCaseId,
                new ExceptionActionRequest(ExceptionActionType.RESOLVE, "근거 확인 후 해결했습니다.", "DOC-001"),
                userId,
                loginId);

        assertThat(started.actionSeq()).isEqualTo(1);
        // SIR-008: 저장 직후 응답의 처리 시각도 GET 조회 경로와 같은 Asia/Seoul 오프셋이어야 한다
        assertThat(started.actionAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
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

        AuditValues resolveAudit = jdbcTemplate.queryForObject("""
                SELECT before_value ->> 'status'       AS before_status,
                       after_value  ->> 'status'       AS after_status,
                       after_value  ->> 'actionType'   AS action_type,
                       after_value  ->> 'evidenceRef'  AS evidence_ref
                  FROM fgc.audit_log
                 WHERE entity_type = 'EXCEPTION_CASE'
                   AND entity_id = ?
                 ORDER BY audit_log_id DESC
                 LIMIT 1
                """, (rs, rowNum) -> new AuditValues(
                rs.getString("before_status"),
                rs.getString("after_status"),
                rs.getString("action_type"),
                rs.getString("evidence_ref"),
                null), String.valueOf(exceptionCaseId));
        assertThat(resolveAudit).isEqualTo(
                new AuditValues("IN_REVIEW", "RESOLVED", "RESOLVE", "DOC-001", null));
    }

    @Test
    void 담당자지정은_감사로그에_상태와_담당자와_조치를_남긴다() {
        Long userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM fgc.app_user ORDER BY user_id LIMIT 1", Long.class);
        String loginId = jdbcTemplate.queryForObject(
                "SELECT login_id FROM fgc.app_user WHERE user_id = ?", String.class, userId);
        Long exceptionCaseId = insertNewCase();

        exceptionCaseService.action(
                exceptionCaseId,
                new ExceptionActionRequest(ExceptionActionType.ASSIGN, "담당자를 지정합니다.", "DOC-ASSIGN-001"),
                userId,
                loginId);

        AuditValues assignAudit = jdbcTemplate.queryForObject("""
                SELECT before_value ->> 'status'       AS before_status,
                       after_value  ->> 'status'       AS after_status,
                       after_value  ->> 'actionType'   AS action_type,
                       after_value  ->> 'evidenceRef'  AS evidence_ref,
                       after_value  ->> 'assignedTo'   AS assigned_to
                  FROM fgc.audit_log
                 WHERE entity_type = 'EXCEPTION_CASE'
                   AND entity_id = ?
                 ORDER BY audit_log_id DESC
                 LIMIT 1
                """, (rs, rowNum) -> new AuditValues(
                rs.getString("before_status"),
                rs.getString("after_status"),
                rs.getString("action_type"),
                rs.getString("evidence_ref"),
                rs.getString("assigned_to")), String.valueOf(exceptionCaseId));

        assertThat(assignAudit).isEqualTo(new AuditValues(
                "NEW", "NEW", "ASSIGN", "DOC-ASSIGN-001", String.valueOf(userId)));
    }

    /** FGC-FUN-053: 실제 이연 조치는 검토중 예외를 해결 상태로 닫는다. */
    @Test
    void 이연은_검토중에서만_허용되고_해결상태로_전이한다() {
        assertThat(ExceptionActionType.DEFER.supports(ExceptionStatus.NEW)).isFalse();
        assertThat(ExceptionActionType.DEFER.supports(ExceptionStatus.IN_REVIEW)).isTrue();

        Long userId = firstUserId();
        String loginId = loginIdOf(userId);
        Long exceptionCaseId = insertNewCase();

        exceptionCaseService.action(
                exceptionCaseId,
                new ExceptionActionRequest(
                        ExceptionActionType.START_REVIEW, "이연 여부를 검토합니다.", "DOC-DEFER-REVIEW"),
                userId,
                loginId);
        var deferred = exceptionCaseService.action(
                exceptionCaseId,
                new ExceptionActionRequest(
                        ExceptionActionType.DEFER, "실제 지급을 미래 회차로 이연합니다.", "DOC-DEFER-001"),
                userId,
                loginId);

        assertThat(deferred.fromStatus()).isEqualTo(ExceptionStatus.IN_REVIEW);
        assertThat(deferred.toStatus()).isEqualTo(ExceptionStatus.RESOLVED);
        assertThat(currentStatus(exceptionCaseId)).isEqualTo("RESOLVED");
    }

    /** FGC-FUN-053: REJECT 해결조치는 검토중 예외를 반려 상태로 닫는다. */
    @Test
    void 반려는_검토중에서_REJECTED로_전이한다() {
        Long userId = firstUserId();
        String loginId = loginIdOf(userId);
        Long exceptionCaseId = insertNewCase();

        exceptionCaseService.action(
                exceptionCaseId,
                new ExceptionActionRequest(
                        ExceptionActionType.START_REVIEW, "반려 여부를 검토합니다.", "DOC-REJECT-REVIEW"),
                userId,
                loginId);
        var rejected = exceptionCaseService.action(
                exceptionCaseId,
                new ExceptionActionRequest(
                        ExceptionActionType.REJECT, "검토 결과 반려합니다.", "DOC-REJECT-001"),
                userId,
                loginId);

        assertThat(rejected.fromStatus()).isEqualTo(ExceptionStatus.IN_REVIEW);
        assertThat(rejected.toStatus()).isEqualTo(ExceptionStatus.REJECTED);
        assertThat(currentStatus(exceptionCaseId)).isEqualTo("REJECTED");
    }

    /** FGC-FUN-053: 오탐·반려 종결 건은 사유를 남긴 REOPEN으로만 재검토한다. */
    @Test
    void 오탐반려는_REOPEN으로_검토중에_복귀하고_이력을_남긴다() {
        Long userId = firstUserId();
        String loginId = loginIdOf(userId);
        Long exceptionCaseId = insertNewCase();

        exceptionCaseService.action(
                exceptionCaseId,
                new ExceptionActionRequest(
                        ExceptionActionType.START_REVIEW, "최초 검토를 시작합니다.", "DOC-REOPEN-START"),
                userId,
                loginId);
        exceptionCaseService.action(
                exceptionCaseId,
                new ExceptionActionRequest(
                        ExceptionActionType.FALSE_POSITIVE, "오탐으로 판단했습니다.", "DOC-REOPEN-REJECT"),
                userId,
                loginId);

        var reopened = exceptionCaseService.action(
                exceptionCaseId,
                new ExceptionActionRequest(
                        ExceptionActionType.REOPEN, "추가 증빙으로 재검토합니다.", "DOC-REOPEN-001"),
                userId,
                loginId);

        assertThat(reopened.fromStatus()).isEqualTo(ExceptionStatus.REJECTED);
        assertThat(reopened.toStatus()).isEqualTo(ExceptionStatus.IN_REVIEW);
        assertThat(reopened.actionType()).isEqualTo("REOPEN");
        assertThat(currentStatus(exceptionCaseId)).isEqualTo("IN_REVIEW");
        assertThat(actionCount(exceptionCaseId)).isEqualTo(3);
        assertThat(auditCount(exceptionCaseId)).isEqualTo(3);
    }

    /** FGC-FUN-047·053: 실제 조치가 끝난 RESOLVED 원장 건은 중복 정정을 막기 위해 직접 재오픈하지 않는다. */
    @Test
    void 해결상태는_REOPEN을_허용하지_않는다() {
        Long userId = firstUserId();
        String loginId = loginIdOf(userId);
        Long exceptionCaseId = insertNewCase();

        exceptionCaseService.action(
                exceptionCaseId,
                new ExceptionActionRequest(
                        ExceptionActionType.START_REVIEW, "해결 여부를 검토합니다.", "DOC-RESOLVE-START"),
                userId,
                loginId);
        exceptionCaseService.action(
                exceptionCaseId,
                new ExceptionActionRequest(
                        ExceptionActionType.RESOLVE, "검토 결과 해결했습니다.", "DOC-RESOLVE-001"),
                userId,
                loginId);

        assertThatThrownBy(() -> exceptionCaseService.action(
                exceptionCaseId,
                new ExceptionActionRequest(
                        ExceptionActionType.REOPEN, "해결 건을 직접 재검토합니다.", "DOC-INVALID-REOPEN"),
                userId,
                loginId))
                .isInstanceOf(FgcBusinessException.class)
                .satisfies(exception -> assertThat(
                        ((FgcBusinessException) exception).getErrorCode())
                        .isEqualTo(FgcErrorCode.EXCP_003));

        assertThat(currentStatus(exceptionCaseId)).isEqualTo("RESOLVED");
        assertThat(actionCount(exceptionCaseId)).isEqualTo(2);
        assertThat(auditCount(exceptionCaseId)).isEqualTo(2);
    }

    /** FGC-FUN-053·FGC-QUR-001: 허용되지 않은 전이는 어떤 이력도 남기지 않는다. */
    @Test
    void 신규상태에서_해결조치를_요청하면_이력과_감사로그를_남기지_않는다() {
        Long userId = firstUserId();
        String loginId = loginIdOf(userId);
        Long exceptionCaseId = insertNewCase();

        assertThatThrownBy(() -> exceptionCaseService.action(
                exceptionCaseId,
                new ExceptionActionRequest(
                        ExceptionActionType.RESOLVE, "검토 없이 해결할 수 없습니다.", "DOC-INVALID-001"),
                userId,
                loginId))
                .isInstanceOf(FgcBusinessException.class)
                .satisfies(exception -> assertThat(
                        ((FgcBusinessException) exception).getErrorCode())
                        .isEqualTo(FgcErrorCode.EXCP_003));

        assertThat(currentStatus(exceptionCaseId)).isEqualTo("NEW");
        assertThat(actionCount(exceptionCaseId)).isZero();
        assertThat(auditCount(exceptionCaseId)).isZero();
    }

    @Test
    void 처리사유가_공백이면_EXCP_001을_반환한다() {
        assertThatThrownBy(() -> exceptionCaseService.action(
                1L,
                new ExceptionActionRequest(ExceptionActionType.START_REVIEW, " ", "DOC-001"),
                1L,
                "settle01"))
                .isInstanceOf(FgcBusinessException.class)
                .satisfies(exception -> assertThat(
                        ((FgcBusinessException) exception).getErrorCode())
                        .isEqualTo(FgcErrorCode.EXCP_001));
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

    private Long firstUserId() {
        return jdbcTemplate.queryForObject(
                "SELECT user_id FROM fgc.app_user ORDER BY user_id LIMIT 1", Long.class);
    }

    private String loginIdOf(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT login_id FROM fgc.app_user WHERE user_id = ?", String.class, userId);
    }

    private String currentStatus(Long exceptionCaseId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM fgc.exception_case WHERE exception_case_id = ?",
                String.class,
                exceptionCaseId);
    }

    private int actionCount(Long exceptionCaseId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.exception_action WHERE exception_case_id = ?",
                Integer.class,
                exceptionCaseId);
        return count == null ? 0 : count;
    }

    private int auditCount(Long exceptionCaseId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM fgc.audit_log
                 WHERE entity_type = 'EXCEPTION_CASE'
                   AND entity_id = ?
                   AND action_code = 'EXCEPTION_ACTION'
                """, Integer.class, String.valueOf(exceptionCaseId));
        return count == null ? 0 : count;
    }

    private record AuditValues(
            String beforeStatus,
            String afterStatus,
            String actionType,
            String evidenceRef,
            String assignedTo
    ) {
    }
}
