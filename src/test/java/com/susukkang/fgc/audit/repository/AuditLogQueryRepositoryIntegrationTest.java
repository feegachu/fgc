package com.susukkang.fgc.audit.repository;

import com.susukkang.fgc.audit.dto.AuditLogRow;
import com.susukkang.fgc.audit.dto.AuditLogResponse;
import com.susukkang.fgc.audit.dto.AuditLogSearchCriteria;
import com.susukkang.fgc.audit.entity.AuditLog;
import com.susukkang.fgc.audit.service.AuditLogQueryService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : FUN-061 감사로그 JPA 조회·저장 통합 테스트.
 * audit_log 는 append-only(trg_audit_log_append_only)라 명시적 DELETE 로 정리할 수 없다 —
 * {@code @Transactional} 롤백으로만 정리한다.
 * 다른 테스트·시드 데이터와 격리하기 위해 행위·대상 코드에 실행별 고유 접미사를 붙인다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-26
 */
@SpringBootTest
@Transactional
class AuditLogQueryRepositoryIntegrationTest {

    /** 시드·데모 데이터와 절대 겹치지 않는 미래 시각 — 기간 필터 검증용. */
    private static final LocalDateTime BASE = LocalDateTime.of(2097, 1, 1, 10, 0);

    @Autowired
    private AuditLogQueryRepository queryRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AuditLogQueryService queryService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private String marker;
    private String actionA;
    private String actionB;
    private String entityTypeA;
    private String entityTypeB;
    private Long userId;
    private String loginId;

    /**
     * 설명 : 모든 선택 조건이 비어 있어도 전체 건수와 페이징 조회가 NULL 바인딩 오류 없이 동작하는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void supportsSearchWithEveryOptionalFilterAbsent() {
        AuditLogSearchCriteria search = criteria(null, null, null, null, null, null);
        long expected = jdbcTemplate.queryForObject("select count(*) from fgc.audit_log", Long.class);

        assertThat(queryRepository.countAuditLogs(search)).isEqualTo(expected);
        assertThat(queryRepository.selectAuditLogs(search, PageRequest.of(0, 2)))
                .hasSize((int) Math.min(expected, 2));
    }

    /**
     * 설명 : 따옴표와 SQL 구문 모양의 입력도 조건식이 아닌 문자 값으로 바인딩되어 정확히 일치하는 행만 반환하는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void treatsQuotedSearchValuesAsLiteralParameters() {
        String quotedId = "quoted-' OR 1=1 --";
        auditLogRepository.saveAndFlush(AuditLog.create(null, actionA, entityTypeA, quotedId,
                null, null, null, null, null, null));
        AuditLogSearchCriteria search = criteria(entityTypeA, quotedId, null, null, null, null);

        assertThat(queryRepository.countAuditLogs(search)).isEqualTo(1);
        assertThat(queryRepository.selectAuditLogs(search, PageRequest.of(0, 20)))
                .singleElement().extracting(AuditLogRow::entityId).isEqualTo(quotedId);
    }

    /**
     * 설명 : 필터와 정렬 검증을 위해 사용자 감사행과 처리자 없는 배치 감사행을 준비한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
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

    /**
     * 설명 : 지정한 발생 시각과 검색 조건을 가진 감사로그 테스트 데이터를 저장한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
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

    /**
     * 설명 : 감사로그 조회 테스트에 사용할 검색 조건 객체를 생성한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    private AuditLogSearchCriteria criteria(
            String entityType, String entityId, Long userId, String action,
            LocalDateTime from, LocalDateTime toExclusive
    ) {
        return new AuditLogSearchCriteria(entityType, entityId, userId, action, from, toExclusive);
    }

    /**
     * 설명 : 대상 종류 필터와 최신순 정렬을 검증하고 처리자 없는 배치 행이 유지되는지 확인한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    @DisplayName("대상 종류 필터 + 최신순 정렬 + LEFT JOIN 으로 배치 행의 userLoginId 는 null")
    void filtersByEntityTypeAndSortsDescending() {
        List<AuditLogRow> rows = queryRepository.selectAuditLogs(
                criteria(entityTypeA, null, null, null, null, null), PageRequest.of(0, 20));

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

    /**
     * 설명 : 대상 ID와 처리자 및 작업 유형 조건별 감사로그 검색 결과를 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    @DisplayName("대상 ID·행위자·행위 종류 필터 (IF-API-52 검색조건 — entityId 는 쿡북에 없던 조건)")
    void filtersByEntityIdUserIdAndAction() {
        assertThat(queryRepository.selectAuditLogs(
                criteria(entityTypeA, "9001", null, null, null, null), PageRequest.of(0, 20)))
                .singleElement()
                .satisfies(row -> assertThat(row.entityId()).isEqualTo("9001"));

        assertThat(queryRepository.selectAuditLogs(
                criteria(entityTypeA, null, userId, null, null, null), PageRequest.of(0, 20)))
                .singleElement()
                .satisfies(row -> assertThat(row.entityId()).isEqualTo("9001"));

        assertThat(queryRepository.selectAuditLogs(
                criteria(null, null, null, actionB, null, null), PageRequest.of(0, 20)))
                .singleElement()
                .satisfies(row -> assertThat(row.actionCode()).isEqualTo(actionB));
    }

    /**
     * 설명 : 감사로그 조회 기간의 시작 시각 포함 및 종료 시각 제외 조건을 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    @DisplayName("기간 필터 — from 포함, toExclusive 미포함")
    void filtersByOccurredAtRange() {
        List<AuditLogRow> rows = queryRepository.selectAuditLogs(
                criteria(entityTypeA, null, null, null, BASE, BASE.plusHours(1)), PageRequest.of(0, 20));

        assertThat(rows).singleElement()
                .satisfies(row -> assertThat(row.entityId()).isEqualTo("9001"));
    }

    /**
     * 설명 : 목록과 전체 건수의 검색 조건 일치 및 페이지 오프셋 동작을 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    @DisplayName("count 는 목록과 같은 조건을 공유하고 페이징은 LIMIT/OFFSET 으로 동작한다")
    void countsAndPaginates() {
        assertThat(queryRepository.countAuditLogs(
                criteria(entityTypeA, null, null, null, null, null))).isEqualTo(2);

        List<AuditLogRow> secondPage = queryRepository.selectAuditLogs(
                criteria(entityTypeA, null, null, null, null, null), PageRequest.of(1, 1));
        assertThat(secondPage).singleElement()
                .satisfies(row -> assertThat(row.entityId()).isEqualTo("9001"));
    }

    /**
     * 설명 : 작업 유형과 대상 종류 및 감사 처리자 선택지 조회를 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    @DisplayName("필터 선택지 — 행위·대상 종류 DISTINCT 와 감사행을 남긴 사용자 목록")
    void returnsFilterOptions() {
        assertThat(queryRepository.selectDistinctActionCodes()).contains(actionA, actionB);
        assertThat(queryRepository.selectDistinctEntityTypes()).contains(entityTypeA, entityTypeB);
        assertThat(queryRepository.selectAuditUsers())
                .anySatisfy(user -> {
                    assertThat(user.userId()).isEqualTo(userId);
                    assertThat(user.loginId()).isEqualTo(loginId);
                });
    }

    /**
     * 설명 : 감사로그에 저장한 정책 버전 ID가 조회 결과에도 유지되는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    @DisplayName("AuditLogRepository.saveAndFlush 가 policy_version_id 를 저장하고 조회로 돌아온다")
    void insertRoundTripsPolicyVersionId() {
        Long policyVersionId = jdbcTemplate.queryForObject(
                "SELECT policy_version_id FROM fgc.policy_version ORDER BY policy_version_id LIMIT 1",
                Long.class);
        String action = "T_PV_" + marker;

        auditLogRepository.saveAndFlush(AuditLog.create(userId, action, entityTypeB, "9100",
                null, "{\"policy\":true}", null, null, null, policyVersionId));

        List<AuditLogRow> rows = queryRepository.selectAuditLogs(
                criteria(null, null, null, action, null, null), PageRequest.of(0, 20));
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.policyVersionId()).isEqualTo(policyVersionId);
            assertThat(row.afterValue()).contains("policy");
        });
    }

    /**
     * 설명 : 중첩 JSON과 SQL null 및 JSON null, IP 주소와 요청 식별 정보의 저장·조회 결과를 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void preservesNestedJsonSqlNullJsonNullIpAndRequestIdentity() {
        String json = "{\"nested\":{\"amount\":12345678901234567890.123456,\"empty\":null},"
                + "\"items\":[true,2,\"가\"],\"payload\":\"" + "긴 값".repeat(300) + "\"}";
        var auditLog = AuditLog.create(userId, actionA, entityTypeA, "json", json, "null",
                "사유", "request-" + marker, "2001:db8::1", null);
        OffsetDateTime before = jdbcTemplate.queryForObject("select clock_timestamp()", OffsetDateTime.class);
        auditLogRepository.saveAndFlush(auditLog);
        OffsetDateTime after = jdbcTemplate.queryForObject("select clock_timestamp()", OffsetDateTime.class);

        AuditLogRow row = queryRepository.selectAuditLogs(
                criteria(entityTypeA, "json", null, null, null, null), PageRequest.of(0, 20)).getFirst();
        assertThat(row.beforeValue()).isEqualTo(jdbcTemplate.queryForObject(
                "select cast(cast(? as jsonb) as text)", String.class, json));
        assertThat(row.afterValue()).isEqualTo("null");
        assertThat(row.userId()).isEqualTo(userId);
        assertThat(row.userLoginId()).isEqualTo(loginId);
        assertThat(row.requestId()).isEqualTo("request-" + marker);
        assertThat(row.reason()).isEqualTo("사유");
        assertThat(row.occurredAt()).isBetween(before, after);
        assertThat(jdbcTemplate.queryForObject("select host(client_ip) from fgc.audit_log where audit_log_id = ?",
                String.class, row.auditLogId())).isEqualTo("2001:db8::1");

        auditLogRepository.saveAndFlush(AuditLog.create(null, actionA, entityTypeA, "null",
                null, null, null, null, "127.0.0.1", null));
        assertThat(queryRepository.selectAuditLogs(
                criteria(entityTypeA, "null", null, null, null, null), PageRequest.of(0, 20)))
                .singleElement().satisfies(batch -> {
                    assertThat(batch.beforeValue()).isNull();
                    assertThat(batch.afterValue()).isNull();
                    assertThat(batch.userId()).isNull();
                    assertThat(batch.userLoginId()).isNull();
                });
    }

    /**
     * 설명 : 배열과 문자열 및 숫자 등의 JSON 값이 이중 인코딩 없이 저장되는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @ParameterizedTest
    @ValueSource(strings = {"[1,{\"nested\":null}]", "\"문자열\"", "true", "12345678901234567890.123456"})
    void persistsJsonValuesWithoutReencoding(String json) {
        auditLogRepository.saveAndFlush(AuditLog.create(null, actionA, entityTypeA, "json-value",
                null, json, null, null, null, null));

        assertThat(queryRepository.selectAuditLogs(
                criteria(entityTypeA, "json-value", null, null, null, null), PageRequest.of(0, 20)))
                .singleElement().satisfies(row -> {
                    assertThat(row.beforeValue()).isNull();
                    assertThat(row.afterValue()).isEqualTo(jdbcTemplate.queryForObject(
                            "select cast(cast(? as jsonb) as text)", String.class, json));
                });
    }

    /**
     * 설명 : IPv4와 IPv6 주소 및 네트워크 접두사가 기존 inet 값으로 저장되는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "2001:db8::1", "192.0.2.10/24", "2001:db8::1/64"})
    void preservesIpAddressAndNetworkPrefix(String clientIp) {
        auditLogRepository.saveAndFlush(AuditLog.create(null, actionA, entityTypeA, "ip",
                null, null, null, null, clientIp, null));
        Long auditId = queryRepository.selectAuditLogs(
                criteria(entityTypeA, "ip", null, null, null, null), PageRequest.of(0, 20)).getFirst().auditLogId();

        assertThat(jdbcTemplate.queryForObject(
                "select client_ip = cast(? as inet) from fgc.audit_log where audit_log_id = ?",
                Boolean.class, clientIp, auditId)).isTrue();
    }

    /**
     * 설명 : 전체 검색 조건 조합과 마이크로초 정밀도 및 동일 시각의 ID 정렬을 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void combinesEveryFilterAndRetainsMicrosecondsAndIdTieBreaker() {
        LocalDateTime time = BASE.plusDays(1).plusNanos(123456000);
        insertRow(time, userId, actionA, entityTypeA, "tie", "older id");
        insertRow(time, userId, actionA, entityTypeA, "tie", "newer id");
        var search = criteria(entityTypeA, "tie", userId, actionA, time, time.plusNanos(1000));

        assertThat(queryRepository.countAuditLogs(search)).isEqualTo(2);
        assertThat(queryRepository.selectAuditLogs(search, PageRequest.of(0, 20)))
                .extracting(AuditLogRow::reason).containsExactly("newer id", "older id");
        assertThat(queryRepository.selectAuditLogs(search, PageRequest.of(1, 1))).singleElement().satisfies(row -> {
            assertThat(row.reason()).isEqualTo("older id");
            assertThat(row.occurredAt().toInstant()).isEqualTo(time.atZone(ZoneId.systemDefault()).toInstant());
        });
        assertThat(queryRepository.selectAuditLogs(search, PageRequest.of(2, 1))).isEmpty();
        var empty = criteria(entityTypeA, "missing", userId, actionA, time, time.plusNanos(1000));
        assertThat(queryRepository.countAuditLogs(empty)).isZero();
        assertThat(queryRepository.selectAuditLogs(empty, PageRequest.of(0, 20))).isEmpty();
    }

    /**
     * 설명 : 내용이 같은 사건도 매번 신규 감사행으로 추가되어 기존 INSERT 동작이 유지되는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void persistsRepeatedEventsAsSeparateAuditRows() {
        for (int i = 0; i < 2; i++) {
            auditLogRepository.saveAndFlush(AuditLog.create(null, actionB, entityTypeB, "repeated",
                    null, "{\"shared\":true}", null, null, null, null));
        }

        var search = criteria(entityTypeB, "repeated", null, null, null, null);
        var rows = queryRepository.selectAuditLogs(search, PageRequest.of(0, 20));
        assertThat(queryRepository.countAuditLogs(search)).isEqualTo(2);
        assertThat(rows).hasSize(2).allSatisfy(row ->
                assertThat(row.afterValue()).isEqualTo("{\"shared\": true}"));
        assertThat(rows).extracting(AuditLogRow::auditLogId).doesNotHaveDuplicates();
    }

    /**
     * 설명 : 서비스 검색의 SQL 실행 횟수와 화면 종료일 포함 조건을 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void serviceUsesTwoStatementsAndKeepsInclusiveEndDate() {
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean enabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            var page = queryService.search(" " + entityTypeA + " ", null, null, " ",
                    BASE.toLocalDate(), BASE.toLocalDate(), 1, 20);
            assertThat(page.content()).hasSize(2);
            assertThat(page.totalElements()).isEqualTo(2);
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
            assertThat(statistics.getEntityFetchCount()).isZero();
        } finally {
            statistics.setStatisticsEnabled(enabled);
        }
    }

    /**
     * 설명 : 기존 SQL과 JPA 조회 응답을 비교하고 동일 조건의 검색 실행 시간을 측정한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void matchesOriginalSqlResponseAndMeasuresSearchCost() {
        // 전환 전 XML의 SELECT를 기준으로 응답 필드·JSON 문자열·정렬·총건수를 비교한다.
        String originalSql = """
                SELECT al.audit_log_id, al.occurred_at, al.user_id, u.login_id AS user_login_id,
                       al.action_code, al.entity_type, al.entity_id,
                       al.before_value::text AS before_value, al.after_value::text AS after_value,
                       al.reason, al.request_id, al.policy_version_id
                  FROM fgc.audit_log al LEFT JOIN fgc.app_user u ON u.user_id = al.user_id
                 WHERE al.entity_type = ? AND al.occurred_at >= ? AND al.occurred_at < ?
                 ORDER BY al.occurred_at DESC, al.audit_log_id DESC LIMIT 20 OFFSET 0
                """;
        var search = criteria(entityTypeA, null, null, null, BASE, BASE.plusDays(1));
        var expected = jdbcTemplate.query(originalSql, DataClassRowMapper.newInstance(AuditLogRow.class),
                entityTypeA, BASE, BASE.plusDays(1)).stream().map(AuditLogResponse::from).toList();
        var actual = queryRepository.selectAuditLogs(search, PageRequest.of(0, 20)).stream().map(AuditLogResponse::from).toList();
        assertThat(actual).isEqualTo(expected);
        assertThat(queryRepository.countAuditLogs(search)).isEqualTo(expected.size());

        long sqlNanos = 0;
        long jpaNanos = 0;
        // 작은 데이터의 동일 조건을 교차 반복한다. 성능 임계값 대신 실행 결과만 기록한다.
        for (int i = 0; i < 30; i++) {
            long start = System.nanoTime();
            jdbcTemplate.query(originalSql, DataClassRowMapper.newInstance(AuditLogRow.class),
                    entityTypeA, BASE, BASE.plusDays(1));
            jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM fgc.audit_log
                    WHERE entity_type = ? AND occurred_at >= ? AND occurred_at < ?
                    """, Long.class, entityTypeA, BASE, BASE.plusDays(1));
            long middle = System.nanoTime();
            queryRepository.selectAuditLogs(search, PageRequest.of(0, 20));
            queryRepository.countAuditLogs(search);
            long end = System.nanoTime();
            if (i >= 5) {
                sqlNanos += middle - start;
                jpaNanos += end - middle;
            }
        }
        LoggerFactory.getLogger(getClass()).info(
                "Audit search comparison: rows={}, samples=25, original SQL mean={}ms, JPA mean={}ms, statements=2/2",
                expected.size(), sqlNanos / 25_000_000.0, jpaNanos / 25_000_000.0);
    }
}
