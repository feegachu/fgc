package com.susukkang.fgc.audit.mapper;

import com.susukkang.fgc.audit.dto.AuditLogInsertRow;
import com.susukkang.fgc.audit.dto.AuditLogRow;
import com.susukkang.fgc.audit.dto.AuditLogSearchCriteria;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FUN-061 감사로그 조회 매퍼 통합 테스트.
 * audit_log 는 append-only(trg_audit_log_append_only)라 명시적 DELETE 로 정리할 수 없다 —
 * {@code @Transactional} 롤백으로만 정리한다.
 * 다른 테스트·시드 데이터와 격리하기 위해 행위·대상 코드에 실행별 고유 접미사를 붙인다.
 */
@SpringBootTest
@Transactional
class AuditLogQueryMapperIntegrationTest {

    /** 시드·데모 데이터와 절대 겹치지 않는 미래 시각 — 기간 필터 검증용. */
    private static final LocalDateTime BASE = LocalDateTime.of(2097, 1, 1, 10, 0);

    @Autowired
    private AuditLogQueryMapper queryMapper;

    @Autowired
    private AuditLogMapper auditLogMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String marker;
    private String actionA;
    private String actionB;
    private String entityTypeA;
    private String entityTypeB;
    private Long userId;
    private String loginId;

    @BeforeEach
    void insertFixtures() {
        marker = UUID.randomUUID().toString().substring(0, 8);
        actionA = "T_ACT_A_" + marker;
        actionB = "T_ACT_B_" + marker;
        entityTypeA = "T_ENT_A_" + marker;
        entityTypeB = "T_ENT_B_" + marker;
        userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM fgc.app_user ORDER BY user_id LIMIT 1", Long.class);
        loginId = jdbcTemplate.queryForObject(
                "SELECT login_id FROM fgc.app_user WHERE user_id = ?", String.class, userId);

        insertRow(BASE, userId, actionA, entityTypeA, "9001", "첫 행");
        insertRow(BASE.plusHours(1), null, actionB, entityTypeA, "9002", null); // 배치 발(user_id NULL)
        insertRow(BASE.plusHours(2), userId, actionA, entityTypeB, "9003", null);
    }

    private void insertRow(
            LocalDateTime occurredAt,
            Long userId,
            String actionCode,
            String entityType,
            String entityId,
            String reason
    ) {
        jdbcTemplate.update("""
                INSERT INTO fgc.audit_log
                       (occurred_at, user_id, action_code, entity_type, entity_id,
                        before_value, after_value, reason, request_id)
                VALUES (?, ?, ?, ?, ?, '{"status":"DRAFT"}'::jsonb, '{"status":"CONFIRMED"}'::jsonb, ?, ?)
                """, occurredAt, userId, actionCode, entityType, entityId, reason, "req-" + marker);
    }

    private AuditLogSearchCriteria criteria(
            String entityType, String entityId, Long userId, String action,
            LocalDateTime from, LocalDateTime toExclusive
    ) {
        return new AuditLogSearchCriteria(entityType, entityId, userId, action, from, toExclusive);
    }

    @Test
    @DisplayName("대상 종류 필터 + 최신순 정렬 + LEFT JOIN 으로 배치 행의 userLoginId 는 null")
    void filtersByEntityTypeAndSortsDescending() {
        List<AuditLogRow> rows = queryMapper.selectAuditLogs(
                criteria(entityTypeA, null, null, null, null, null), 0, 20);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).entityId()).isEqualTo("9002"); // 나중 시각이 먼저
        assertThat(rows.get(0).userId()).isNull();
        assertThat(rows.get(0).userLoginId()).isNull();       // 화면 표기 "BATCH"
        assertThat(rows.get(1).entityId()).isEqualTo("9001");
        assertThat(rows.get(1).userLoginId()).isEqualTo(loginId);
        assertThat(rows.get(1).beforeValue()).contains("DRAFT");
        assertThat(rows.get(1).afterValue()).contains("CONFIRMED");
        assertThat(rows.get(1).reason()).isEqualTo("첫 행");
        assertThat(rows.get(1).requestId()).isEqualTo("req-" + marker);
    }

    @Test
    @DisplayName("대상 ID·행위자·행위 종류 필터 (IF-API-52 검색조건 — entityId 는 쿡북에 없던 조건)")
    void filtersByEntityIdUserIdAndAction() {
        assertThat(queryMapper.selectAuditLogs(
                criteria(entityTypeA, "9001", null, null, null, null), 0, 20))
                .singleElement()
                .satisfies(row -> assertThat(row.entityId()).isEqualTo("9001"));

        assertThat(queryMapper.selectAuditLogs(
                criteria(entityTypeA, null, userId, null, null, null), 0, 20))
                .singleElement()
                .satisfies(row -> assertThat(row.entityId()).isEqualTo("9001"));

        assertThat(queryMapper.selectAuditLogs(
                criteria(null, null, null, actionB, null, null), 0, 20))
                .singleElement()
                .satisfies(row -> assertThat(row.actionCode()).isEqualTo(actionB));
    }

    @Test
    @DisplayName("기간 필터 — from 포함, toExclusive 미포함")
    void filtersByOccurredAtRange() {
        List<AuditLogRow> rows = queryMapper.selectAuditLogs(
                criteria(entityTypeA, null, null, null, BASE, BASE.plusHours(1)), 0, 20);

        assertThat(rows).singleElement()
                .satisfies(row -> assertThat(row.entityId()).isEqualTo("9001"));
    }

    @Test
    @DisplayName("count 는 목록과 같은 조건을 공유하고 페이징은 LIMIT/OFFSET 으로 동작한다")
    void countsAndPaginates() {
        assertThat(queryMapper.countAuditLogs(
                criteria(entityTypeA, null, null, null, null, null))).isEqualTo(2);

        List<AuditLogRow> secondPage = queryMapper.selectAuditLogs(
                criteria(entityTypeA, null, null, null, null, null), 1, 1);
        assertThat(secondPage).singleElement()
                .satisfies(row -> assertThat(row.entityId()).isEqualTo("9001"));
    }

    @Test
    @DisplayName("필터 선택지 — 행위·대상 종류 DISTINCT 와 감사행을 남긴 사용자 목록")
    void returnsFilterOptions() {
        assertThat(queryMapper.selectDistinctActionCodes()).contains(actionA, actionB);
        assertThat(queryMapper.selectDistinctEntityTypes()).contains(entityTypeA, entityTypeB);
        assertThat(queryMapper.selectAuditUsers())
                .anySatisfy(user -> {
                    assertThat(user.userId()).isEqualTo(userId);
                    assertThat(user.loginId()).isEqualTo(loginId);
                });
    }

    @Test
    @DisplayName("AuditLogMapper.insert 가 policy_version_id 를 저장하고 조회로 돌아온다")
    void insertRoundTripsPolicyVersionId() {
        Long policyVersionId = jdbcTemplate.queryForObject(
                "SELECT policy_version_id FROM fgc.policy_version ORDER BY policy_version_id LIMIT 1",
                Long.class);
        String action = "T_PV_" + marker;

        int affected = auditLogMapper.insert(AuditLogInsertRow.builder()
                .userId(userId)
                .actionCode(action)
                .entityType(entityTypeB)
                .entityId("9100")
                .beforeValue(null)
                .afterValue("{\"policy\":true}")
                .policyVersionId(policyVersionId)
                .build());

        assertThat(affected).isEqualTo(1);
        List<AuditLogRow> rows = queryMapper.selectAuditLogs(
                criteria(null, null, null, action, null, null), 0, 20);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.policyVersionId()).isEqualTo(policyVersionId);
            assertThat(row.afterValue()).contains("policy");
        });
    }
}
