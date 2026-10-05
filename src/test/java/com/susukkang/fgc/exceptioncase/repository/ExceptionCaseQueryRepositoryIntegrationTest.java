package com.susukkang.fgc.exceptioncase.repository;

import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.dashboard.service.DashboardService;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseSearchRow;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseSearchDTO;
import com.susukkang.fgc.exceptioncase.dto.ExceptionActionRequest;
import com.susukkang.fgc.exceptioncase.dto.ExceptionActionRow;
import com.susukkang.fgc.exceptioncase.dto.ExceptionOccurrenceRow;
import com.susukkang.fgc.exceptioncase.dto.ExceptionActionResponse;
import com.susukkang.fgc.exceptioncase.dto.ExceptionOccurrenceResponse;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseResponseDTO;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseSearchResponse;
import com.susukkang.fgc.exceptioncase.dto.ExceptionTypeSummaryRow;
import com.susukkang.fgc.exceptioncase.dto.ExceptionTypeSummaryResponse;
import com.susukkang.fgc.exceptioncase.entity.ExceptionAction;
import com.susukkang.fgc.exceptioncase.entity.ExceptionCase;
import com.susukkang.fgc.exceptioncase.service.ExceptionCaseService;
import com.susukkang.fgc.common.code.ExceptionActionType;
import com.susukkang.fgc.common.code.ExceptionSeverity;
import com.susukkang.fgc.common.code.ExceptionType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.ClassPathResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #83: EXCP-W01 예외함 목록 조회 — 화면 필터 3가지 경로(OPEN / 개별 상태값 / 필터 없음)가
 * ExceptionStatus.dbStatuses() 로 풀려 실제 SQL 에서 맞게 걸리는지, 그리고 미처리 건수가
 * 대시보드 서비스의 KPI와 같은 기준인지 지킨다.
 */
@SpringBootTest(properties = {
        "fgc.batch.daily-changed-contract.enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
@Transactional
class ExceptionCaseQueryRepositoryIntegrationTest {

    @Autowired
    private ExceptionCaseQueryRepository repository;

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired private DataSource dataSource;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private EntityManager entityManager;
    @Autowired private ExceptionCaseService service;
    @Autowired private ExceptionCaseRepository cases;
    @Autowired private ExceptionActionRepository actions;

    private String suffix;
    private String contractNo;
    private Long contractId;

    @BeforeEach
    void insertFixtures() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        contractNo = "IT-EXCP-" + suffix;
        contractId = insertContract(contractNo);
        insertCase("NEW", "CRITICAL", "IT 신규-" + suffix);
        insertCase("IN_REVIEW", "WARNING", "IT 검토중-" + suffix);
        insertCase("RESOLVED", "INFO", "IT 해결-" + suffix);
    }

    private Long insertContract(String uniqueContractNo) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.insurance_contract (
                    insurer_id, product_offering_id, contract_no, contract_date,
                    agent_id, organization_id, premium_per_cycle_amount,
                    first_premium_amount, monthly_equivalent_first_premium,
                    premium_conversion_rule_code, payment_cycle_code,
                    payment_term_months, current_status, data_origin
                )
                SELECT insurer_id, product_offering_id, ?, contract_date,
                       agent_id, organization_id, premium_per_cycle_amount,
                       first_premium_amount, monthly_equivalent_first_premium,
                       premium_conversion_rule_code, payment_cycle_code,
                       payment_term_months, 'ACTIVE', 'MANUAL'
                  FROM fgc.insurance_contract
                 ORDER BY contract_id
                 LIMIT 1
                RETURNING contract_id
                """, Long.class, uniqueContractNo);
    }

    private void insertCase(String status, String severity, String title) {
        insertCase("DATA_QUALITY", status, severity, title);
    }

    private void insertCase(String exceptionType, String status, String severity, String title) {
        jdbcTemplate.update("""
                INSERT INTO fgc.exception_case
                       (exception_key, exception_type, severity, status,
                        source_entity_type, source_entity_id, title, contract_id)
                VALUES (?, ?, ?, ?, 'IT', ?, ?, ?)
                """, "IT:" + suffix + ":" + exceptionType + ":" + status,
                exceptionType, severity, status, suffix, title, contractId);
    }

    private List<String> myTitles(List<ExceptionCaseSearchRow> rows) {
        return rows.stream().map(ExceptionCaseSearchRow::title)
                .filter(t -> t.endsWith(suffix)).toList();
    }

    @Test
    void OPEN_필터는_신규와_검토중만_조회한다() {
        var rows = repository.search(ExceptionCaseSearchDTO.builder().contractNo(contractNo).build(),
                ExceptionStatus.dbStatuses("OPEN"), 0, 100);
        assertThat(myTitles(rows)).containsExactlyInAnyOrder("IT 신규-" + suffix, "IT 검토중-" + suffix);
    }

    @Test
    void 실제_상태코드_필터는_그_상태만_조회한다() {
        var rows = repository.search(ExceptionCaseSearchDTO.builder().contractNo(contractNo).build(),
                ExceptionStatus.dbStatuses("RESOLVED"), 0, 100);
        assertThat(myTitles(rows)).containsExactly("IT 해결-" + suffix);
    }

    @Test
    void 필터_없음은_전체를_조회한다() {
        var rows = repository.search(ExceptionCaseSearchDTO.builder().contractNo(contractNo).build(),
                ExceptionStatus.dbStatuses(""), 0, 100);
        assertThat(myTitles(rows)).hasSize(3);
    }

    /** FUN-057: 대시보드 '미처리 예외' 카드 건수와 예외함 미처리 건수는 같은 기준이어야 한다. */
    @Test
    void 미처리_건수는_대시보드_KPI_집계와_일치한다() {
        long screen = repository.count(new ExceptionCaseSearchDTO(),
                ExceptionStatus.dbStatuses(ExceptionStatus.OPEN_FILTER));
        assertThat(screen).isEqualTo(dashboardService.summarize(LocalDate.of(2031, 1, 1)).kpis().openException());
    }

    /** EXCP-W01 담당자 필터 — 배정 건만 / 미배정 건만, 그리고 선택지에는 배정 이력 사용자만. */
    @Test
    void 담당자_필터와_미배정_필터는_배정_기준으로_나뉜다() {
        Long userId = anyUserId();
        jdbcTemplate.update("""
                UPDATE fgc.exception_case SET assigned_to = ?
                 WHERE source_entity_type = 'IT' AND source_entity_id = ? AND status = 'NEW'
                """, userId, suffix);

        ExceptionCaseSearchDTO assigned = ExceptionCaseSearchDTO.builder()
                .assignee(userId).contractNo(contractNo).build();
        assertThat(repository.search(assigned, ExceptionStatus.dbStatuses(""), 0, 100)
                .stream().map(row -> row.title()))
                .containsExactly("IT 신규-" + suffix);

        ExceptionCaseSearchDTO unassigned = ExceptionCaseSearchDTO.builder()
                .unassignedOnly(true).contractNo(contractNo).build();
        assertThat(repository.search(unassigned, ExceptionStatus.dbStatuses(""), 0, 100)
                .stream().map(row -> row.title()))
                .containsExactlyInAnyOrder("IT 검토중-" + suffix, "IT 해결-" + suffix);

        // 미배정 우선: 두 조건이 함께 오면 unassignedOnly 가 이긴다 (choose 구조) —
        // count 와 search 가 같은 조건을 타는지 둘 다 검증한다.
        ExceptionCaseSearchDTO both = ExceptionCaseSearchDTO.builder()
                .assignee(userId).unassignedOnly(true).contractNo(contractNo).build();
        assertThat(repository.count(both, ExceptionStatus.dbStatuses(""))).isEqualTo(2L);
        assertThat(repository.search(both, ExceptionStatus.dbStatuses(""), 0, 100)
                .stream().map(row -> row.title()))
                .containsExactlyInAnyOrder("IT 검토중-" + suffix, "IT 해결-" + suffix);

        // FGC-FUN-053(담당자 기록)·IF-API-43: 선택지 = 배정 이력 있는 사용자 집합과
        // 정확히 일치 — 배정 없는 사용자는 나오지 않는다
        List<Long> assignedUserIds = jdbcTemplate.queryForList("""
                SELECT DISTINCT assigned_to
                  FROM fgc.exception_case
                 WHERE assigned_to IS NOT NULL
                """, Long.class);
        assertThat(repository.findAssignees())
                .extracting(assigneeRow -> assigneeRow.userId())
                .containsExactlyInAnyOrderElementsOf(assignedUserIds)
                .contains(userId);
        Long unassignedUserId = jdbcTemplate.queryForObject("""
                SELECT au.user_id FROM fgc.app_user au
                 WHERE au.user_id <> ?
                   AND NOT EXISTS (SELECT 1 FROM fgc.exception_case ec
                                    WHERE ec.assigned_to = au.user_id)
                 ORDER BY au.user_id LIMIT 1
                """, Long.class, userId);
        assertThat(repository.findAssignees())
                .noneMatch(assigneeRow -> assigneeRow.userId().equals(unassignedUserId));
    }

    /** FGC-FUN-052·VRUN-W02 '예외함 열기' 링크의 검증월 검색조건 — 연도 경계(12월↔1월)도 섞이지 않는다. */
    @Test
    void 검증월_필터는_해당_월_검출건만_조회한다() {
        java.time.LocalDate january = java.time.LocalDate.of(2031, 1, 1);
        java.time.LocalDate december = java.time.LocalDate.of(2030, 12, 1);
        jdbcTemplate.update("""
                UPDATE fgc.exception_case SET validation_month = ?
                 WHERE source_entity_type = 'IT' AND source_entity_id = ? AND status = 'NEW'
                """, january, suffix);
        jdbcTemplate.update("""
                UPDATE fgc.exception_case SET validation_month = ?
                 WHERE source_entity_type = 'IT' AND source_entity_id = ? AND status = 'IN_REVIEW'
                """, december, suffix);

        ExceptionCaseSearchDTO criteria = ExceptionCaseSearchDTO.builder()
                .validationMonth(january).contractNo(contractNo).build();
        assertThat(repository.search(criteria, ExceptionStatus.dbStatuses(""), 0, 100)
                .stream().map(row -> row.title()))
                .containsExactly("IT 신규-" + suffix);
        assertThat(repository.count(criteria, ExceptionStatus.dbStatuses(""))).isEqualTo(1L);

        ExceptionCaseSearchDTO decemberCriteria = ExceptionCaseSearchDTO.builder()
                .validationMonth(december).contractNo(contractNo).build();
        assertThat(repository.search(decemberCriteria, ExceptionStatus.dbStatuses(""), 0, 100)
                .stream().map(row -> row.title()))
                .containsExactly("IT 검토중-" + suffix);
        assertThat(repository.count(decemberCriteria, ExceptionStatus.dbStatuses(""))).isEqualTo(1L);

        assertThat(repository.findValidationMonths()).contains(january, december);
    }

    private Long anyUserId() {
        return jdbcTemplate.queryForObject(
                "SELECT user_id FROM fgc.app_user ORDER BY user_id LIMIT 1", Long.class);
    }

    /** IF-API-43: 새 검색 DTO와 페이징 SQL이 OPEN 묶음 및 유형 조건을 함께 적용한다. */
    @Test
    void API_검색은_조건과_페이징을_적용한다() {
        // 같은 계약의 OPEN 상태지만 유형이 다른 방해 데이터를 두어 type 조건 누락도 검출한다.
        insertCase("OTHER", "NEW", "INFO", "IT 다른유형-" + suffix);

        ExceptionCaseSearchDTO criteria = ExceptionCaseSearchDTO.builder()
                .type(com.susukkang.fgc.common.code.ExceptionType.DATA_QUALITY)
                .status(ExceptionStatus.OPEN_FILTER)
                .contractNo(contractNo)
                .build();

        var rows = repository.search(
                criteria,
                ExceptionStatus.dbStatuses(criteria.getStatus()),
                0,
                100);

        assertThat(rows.stream().map(row -> row.title()).filter(t -> t.endsWith(suffix)))
                .containsExactlyInAnyOrder("IT 신규-" + suffix, "IT 검토중-" + suffix);
        assertThat(repository.count(criteria, ExceptionStatus.dbStatuses(criteria.getStatus())))
                .isEqualTo(2L);
        assertThat(repository.countOpenByType()).isNotEmpty();

        List<Long> caseIds = jdbcTemplate.queryForList("""
                SELECT exception_case_id
                  FROM fgc.exception_case
                 WHERE source_entity_type = 'IT'
                   AND source_entity_id = ?
                """, Long.class, suffix);
        assertThat(repository.findActionsByCaseIds(caseIds)).isEmpty();
    }

    /** 전환 전 XML을 테스트 전용으로 실행하여 필터·순서·null·JSON·연결 값을 비교한다. */
    @Test
    void matchesLegacySearchSummaryOptionsAndHistories() throws Exception {
        Long userId = anyUserId();
        Long runId = insertRun();
        Long caseId = firstCaseId();
        jdbcTemplate.update("""
                UPDATE fgc.exception_case
                   SET reason_code = ?, validation_run_id = ?, validation_month = DATE '2031-01-01',
                       assigned_to = ?, agent_id = (SELECT agent_id FROM fgc.insurance_contract WHERE contract_id = ?)
                 WHERE exception_case_id = ?
                """, suffix, runId, userId, contractId, caseId);
        insertHistories(List.of(caseId), runId, 3);
        SqlSessionTemplate baseline = baselineSession();

        var all = ExceptionCaseSearchDTO.builder().contractNo(contractNo.toLowerCase(java.util.Locale.ROOT)).build();
        var combined = ExceptionCaseSearchDTO.builder().contractNo(contractNo)
                .type(ExceptionType.DATA_QUALITY).types(List.of(ExceptionType.DATA_QUALITY, ExceptionType.OTHER))
                .reasonCode(suffix).severity(ExceptionSeverity.CRITICAL).assignee(userId)
                .validationRunId(runId).validationMonth(LocalDate.of(2031, 1, 1)).build();
        var empty = ExceptionCaseSearchDTO.builder().contractNo("' OR 1=1 --").build();
        var unassigned = ExceptionCaseSearchDTO.builder().contractNo(contractNo)
                .assignee(userId).unassignedOnly(true).build();
        var whitespace = ExceptionCaseSearchDTO.builder().reasonCode(" ").contractNo(" ").build();
        var severityOnly = ExceptionCaseSearchDTO.builder().severity(ExceptionSeverity.CRITICAL).build();
        var reasonOnly = ExceptionCaseSearchDTO.builder().reasonCode(suffix).build();
        var runOnly = ExceptionCaseSearchDTO.builder().validationRunId(runId).build();
        var typesOnly = ExceptionCaseSearchDTO.builder()
                .types(List.of(ExceptionType.DATA_QUALITY, ExceptionType.OTHER)).build();
        var assigneeOnly = ExceptionCaseSearchDTO.builder().assignee(userId).build();
        for (var criteria : List.of(all, combined, empty, unassigned, whitespace,
                severityOnly, reasonOnly, runOnly, typesOnly, assigneeOnly)) {
            for (var statuses : List.of(List.<ExceptionStatus>of(), ExceptionStatus.dbStatuses("OPEN"),
                    List.of(ExceptionStatus.NEW, ExceptionStatus.RESOLVED))) {
                for (int offset : List.of(0, 1, 10000)) {
                    Map<String, Object> params = Map.of("criteria", criteria, "statuses", statuses,
                            "offset", offset, "limit", 20);
                    assertEquivalent(repository.search(criteria, statuses, offset, 20),
                            baseline.selectList("exceptionCaseBaseline.search", params));
                    assertThat(repository.count(criteria, statuses)).isEqualTo(
                            baseline.<Long>selectOne("exceptionCaseBaseline.count", params));
                }
            }
        }
        assertThat(repository.search(combined, List.of(), 0, 20)).hasSize(1);
        assertThat(repository.count(empty, List.of())).isZero();
        assertEquivalent(repository.countOpenByType(), baseline.selectList("exceptionCaseBaseline.countOpenByType"));
        assertEquivalent(repository.findReasonCodes(), baseline.selectList("exceptionCaseBaseline.findReasonCodes"));
        assertEquivalent(repository.findAssignees(), baseline.selectList("exceptionCaseBaseline.findAssignees"));
        assertEquivalent(repository.findValidationMonths(), baseline.selectList("exceptionCaseBaseline.findValidationMonths"));
        Map<String, Object> ids = Map.of("exceptionCaseIds", caseIds());
        assertEquivalent(repository.findActionsByCaseIds(caseIds()),
                baseline.<ExceptionActionRow>selectList("exceptionCaseBaseline.findActionsByCaseIds", ids));
        assertEquivalent(repository.findOccurrencesByCaseIds(caseIds()),
                baseline.<ExceptionOccurrenceRow>selectList("exceptionCaseBaseline.findOccurrencesByCaseIds", ids));
        assertThat(repository.findActionsByCaseIds(List.of(caseId)))
                .extracting(ExceptionActionRow::actionSeq).containsExactly(1, 2, 3);
        assertThat(repository.findActionsByCaseIds(List.of(caseId)).getFirst().fromStatus()).isNull();
        assertThat(repository.findOccurrencesByCaseIds(List.of(caseId)))
                .extracting(ExceptionOccurrenceRow::exceptionOccurrenceId).isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(repository.findOccurrencesByCaseIds(List.of(caseId)).getFirst().evidenceJson())
                .contains("null");
    }

    /** 테스트 전용 DB에서 빈 선택지·미처리 0건의 반환 계약을 확인하고 전부 롤백한다. */
    @Test
    void returnsEmptySummaryAndOptionsWhenNoEligibleRowsExist() throws Exception {
        jdbcTemplate.update("""
                UPDATE fgc.exception_case
                   SET reason_code = NULL, assigned_to = NULL, validation_month = NULL,
                       status = 'RESOLVED', resolved_at = CURRENT_TIMESTAMP
                """);
        SqlSessionTemplate baseline = baselineSession();
        assertThat(repository.countOpenByType()).isEmpty();
        assertThat(repository.findReasonCodes()).isEmpty();
        assertThat(repository.findAssignees()).isEmpty();
        assertThat(repository.findValidationMonths()).isEmpty();
        assertEquivalent(repository.countOpenByType(), baseline.selectList("exceptionCaseBaseline.countOpenByType"));
        assertEquivalent(repository.findReasonCodes(), baseline.selectList("exceptionCaseBaseline.findReasonCodes"));
        assertEquivalent(repository.findAssignees(), baseline.selectList("exceptionCaseBaseline.findAssignees"));
        assertEquivalent(repository.findValidationMonths(), baseline.selectList("exceptionCaseBaseline.findValidationMonths"));
    }

    @Test
    void keepsStableSortingAndClampsPagesBeforeFetching() {
        jdbcTemplate.update("""
                UPDATE fgc.exception_case SET severity = 'HIGH', created_at = TIMESTAMPTZ '2031-01-01 00:00:00+09'
                 WHERE contract_id = ?
                """, contractId);
        var criteria = ExceptionCaseSearchDTO.builder().contractNo(contractNo).build();
        assertThat(repository.search(criteria, List.of(), 0, 100))
                .extracting(ExceptionCaseSearchRow::exceptionCaseId).isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(service.search(criteria, 1, 1).content().getFirst().exceptionCaseId())
                .isEqualTo(caseIds().getLast());
        assertThat(service.search(criteria, 2, 1).page()).isEqualTo(2);
        var last = service.search(criteria, Integer.MAX_VALUE, 1);
        assertThat(last.page()).isEqualTo(3);
        assertThat(last.totalElements()).isEqualTo(3);
        assertThat(last.content().getFirst().exceptionCaseId()).isEqualTo(caseIds().getFirst());
        assertThat(service.search(ExceptionCaseSearchDTO.builder().contractNo(suffix + "-missing").build(),
                Integer.MAX_VALUE, 20).page()).isEqualTo(1);
    }

    @Test
    void historyQueryCountStaysConstantAsPageAndHistorySizesGrow() throws Exception {
        Long runId = insertRun();
        jdbcTemplate.update("""
                INSERT INTO fgc.exception_case
                    (exception_key, exception_type, severity, source_entity_type, source_entity_id, title, contract_id)
                SELECT ? || n, 'DATA_QUALITY', 'INFO', 'IT', CAST(n AS varchar), 'bulk', ?
                  FROM generate_series(1, 27) n
                """, "IT-N1:" + suffix + ":", contractId);
        insertHistories(caseIds(), runId, 4);
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        var criteria = ExceptionCaseSearchDTO.builder().contractNo(contractNo).build();
        for (int size : List.of(1, 10, 30)) {
            long statements = statistics.getPrepareStatementCount();
            long entities = statistics.getEntityLoadCount();
            long started = System.nanoTime();
            var result = service.search(criteria, 1, size);
            long elapsed = System.nanoTime() - started;
            assertThat(statistics.getPrepareStatementCount() - statements).isEqualTo(5);
            assertThat(statistics.getEntityLoadCount() - entities).isZero();
            assertThat(result.content()).hasSize(size).allSatisfy(row -> {
                assertThat(row.actions()).hasSize(4);
                assertThat(row.occurrences()).hasSize(4);
            });
            System.out.printf(java.util.Locale.ROOT, "EXCEPTION_READ pageSize=%d sql=5 elapsed_ms=%.3f%n",
                    size, elapsed / 1_000_000.0);
        }
        long statements = statistics.getPrepareStatementCount();
        assertThat(repository.findActionsByCaseIds(List.of())).isEmpty();
        assertThat(repository.findOccurrencesByCaseIds(List.of())).isEmpty();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(statements);
        statements = statistics.getPrepareStatementCount();
        var empty = service.search(ExceptionCaseSearchDTO.builder().contractNo(suffix + "-missing").build(), 9, 20);
        assertThat(empty.content()).isEmpty();
        assertThat(empty.page()).isEqualTo(1);
        assertThat(statistics.getPrepareStatementCount() - statements).isEqualTo(2);

        // 동일한 30건·각 4개 이력을 전환 전후 교차 실행한다. 시간은 합격 기준으로 사용하지 않는다.
        AtomicInteger legacySqlCount = new AtomicInteger();
        SqlSessionTemplate baseline = baselineSession(legacySqlCount);
        assertEquivalent(service.search(criteria, 1, 30), readLegacyPage(baseline, criteria, 30));
        List<Long> oldSamples = new java.util.ArrayList<>();
        List<Long> newSamples = new java.util.ArrayList<>();
        for (int round = 0; round < 13; round++) {
            for (int turn = 0; turn < 2; turn++) {
                boolean legacy = (round + turn) % 2 == 0;
                long before = legacy ? legacySqlCount.get() : statistics.getPrepareStatementCount();
                long started = System.nanoTime();
                if (legacy) {
                    readLegacyPage(baseline, criteria, 30);
                } else {
                    service.search(criteria, 1, 30);
                }
                long elapsed = System.nanoTime() - started;
                long after = legacy ? legacySqlCount.get() : statistics.getPrepareStatementCount();
                assertThat(after - before).isEqualTo(5);
                if (round >= 3) {
                    (legacy ? oldSamples : newSamples).add(elapsed);
                }
            }
        }
        oldSamples.sort(Long::compareTo);
        newSamples.sort(Long::compareTo);
        System.out.printf(java.util.Locale.ROOT,
                "EXCEPTION_READ_COMPARE cases=30 histories=4 sql_old=5 sql_new=5 old_p50_ms=%.3f new_p50_ms=%.3f samples=10%n",
                oldSamples.get(4) / 1_000_000.0, newSamples.get(4) / 1_000_000.0);
    }

    private ExceptionCaseSearchResponse readLegacyPage(SqlSessionTemplate baseline, ExceptionCaseSearchDTO criteria, int size) {
        Map<String, Object> params = Map.of("criteria", criteria, "statuses", List.of(), "offset", 0, "limit", size);
        long total = baseline.selectOne("exceptionCaseBaseline.count", params);
        List<ExceptionCaseSearchRow> rows = baseline.selectList("exceptionCaseBaseline.search", params);
        Map<String, Object> ids = Map.of("exceptionCaseIds", rows.stream().map(ExceptionCaseSearchRow::exceptionCaseId).toList());
        var actionRows = baseline.<ExceptionActionRow>selectList("exceptionCaseBaseline.findActionsByCaseIds", ids)
                .stream().collect(Collectors.groupingBy(ExceptionActionRow::exceptionCaseId,
                        Collectors.mapping(ExceptionActionResponse::from, Collectors.toList())));
        var occurrenceRows = baseline.<ExceptionOccurrenceRow>selectList("exceptionCaseBaseline.findOccurrencesByCaseIds", ids)
                .stream().collect(Collectors.groupingBy(ExceptionOccurrenceRow::exceptionCaseId,
                        Collectors.mapping(ExceptionOccurrenceResponse::from, Collectors.toList())));
        var summary = baseline.<ExceptionTypeSummaryRow>selectList("exceptionCaseBaseline.countOpenByType")
                .stream().map(ExceptionTypeSummaryResponse::from).toList();
        var content = rows.stream().map(row -> ExceptionCaseResponseDTO.from(row,
                occurrenceRows.getOrDefault(row.exceptionCaseId(), List.of()),
                actionRows.getOrDefault(row.exceptionCaseId(), List.of()))).toList();
        return new ExceptionCaseSearchResponse(summary, content, 1, size, total,
                (int) ((total + size - 1) / size), "severity,asc,createdAt,desc");
    }

    /** JPA 저장·벌크 조치와 조회를 연결해도 관리 엔티티의 이전 상태를 반환하지 않는다. */
    @Test
    void readsJpaChangesAndActionsWithinTheSameTransaction() {
        var saved = cases.save(ExceptionCase.builder().exceptionKey("IT-JPA:" + suffix)
                .exceptionType(ExceptionType.DATA_QUALITY).severity(ExceptionSeverity.WARNING)
                .sourceEntityType("IT").sourceEntityId(suffix).title("JPA")
                .contractId(contractId).build());
        Long caseId = saved.getExceptionCaseId();
        Long userId = anyUserId();
        var at = OffsetDateTime.parse("2031-01-01T09:00:00+09:00");
        actions.save(ExceptionAction.builder().exceptionCaseId(caseId).actionSeq(1)
                .fromStatus(ExceptionStatus.NEW).toStatus(ExceptionStatus.IN_REVIEW)
                .actionType(ExceptionActionType.START_REVIEW).reason("JPA action")
                .actionBy(userId).actionAt(at).build());
        cases.updateCaseAfterAction(caseId, ExceptionStatus.IN_REVIEW, userId, null);
        var criteria = ExceptionCaseSearchDTO.builder().contractNo(contractNo).build();
        var result = service.search(criteria, 1, 100).content().stream()
                .filter(row -> row.exceptionCaseId().equals(caseId)).findFirst().orElseThrow();
        assertThat(result.status()).isEqualTo(ExceptionStatus.IN_REVIEW);
        assertThat(result.assignedTo()).isEqualTo(userId);
        assertThat(result.actions()).hasSize(1);
        assertThat(result.actions().getFirst().actionAt().toInstant()).isEqualTo(at.toInstant());
        assertThat(saved.getStatus()).isEqualTo(ExceptionStatus.NEW); // 벌크 갱신 후 오래된 관리 엔티티

        String loginId = jdbcTemplate.queryForObject("SELECT login_id FROM fgc.app_user WHERE user_id = ?",
                String.class, userId);
        service.action(caseId, new ExceptionActionRequest(ExceptionActionType.RESOLVE, "resolved", null), userId, loginId);
        var resolved = service.search(criteria, 1, 100).content().stream()
                .filter(row -> row.exceptionCaseId().equals(caseId)).findFirst().orElseThrow();
        assertThat(resolved.status()).isEqualTo(ExceptionStatus.RESOLVED);
        assertThat(resolved.actions()).hasSize(2);
        entityManager.clear();
    }

    private Long insertRun() {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type, status)
                SELECT DATE '2031-01-01', COALESCE(MAX(run_no), 0) + 1, 'MONTHLY', 'CREATED'
                  FROM fgc.validation_run WHERE validation_month = DATE '2031-01-01'
                RETURNING validation_run_id
                """, Long.class);
        jdbcTemplate.update("UPDATE fgc.validation_run SET status = 'RUNNING' WHERE validation_run_id = ?", id);
        jdbcTemplate.update("UPDATE fgc.validation_run SET status = 'COMPLETED', current_step = 8 WHERE validation_run_id = ?", id);
        return id;
    }

    private List<Long> caseIds() {
        return jdbcTemplate.queryForList("SELECT exception_case_id FROM fgc.exception_case WHERE contract_id = ? ORDER BY exception_case_id",
                Long.class, contractId);
    }

    private Long firstCaseId() {
        return caseIds().getFirst();
    }

    private void insertHistories(List<Long> ids, Long runId, int count) {
        List<Long> runIds = new java.util.ArrayList<>(List.of(runId));
        for (int seq = 2; seq <= count; seq++) {
            runIds.add(insertRun());
        }
        for (Long id : ids) {
            for (int seq = 1; seq <= count; seq++) {
                jdbcTemplate.update("""
                        INSERT INTO fgc.exception_action
                            (exception_case_id, action_seq, from_status, to_status, action_type, reason, action_by)
                        VALUES (?, ?, ?, 'NEW', 'COMMENT', 'history', ?)
                        """, id, seq, seq == 1 ? null : "NEW", anyUserId());
                jdbcTemplate.update("""
                        INSERT INTO fgc.exception_occurrence
                            (exception_case_id, validation_run_id, exception_type, source_entity_type,
                             source_entity_id, evidence_snapshot, is_new_case, was_reopened, detected_at)
                        VALUES (?, ?, 'DATA_QUALITY', 'IT', ?, '{"result":null,"amount":123}'::jsonb,
                                ?, ?, TIMESTAMPTZ '2031-01-01 09:00:00+09')
                        """, id, runIds.get(seq - 1), "source-" + seq, seq == 1, seq == count);
            }
        }
    }

    private SqlSessionTemplate baselineSession() throws Exception {
        return baselineSession(new AtomicInteger());
    }

    private SqlSessionTemplate baselineSession(AtomicInteger statements) throws Exception {
        Configuration config = new Configuration(new Environment("exception-baseline",
                new SpringManagedTransactionFactory(), dataSource));
        config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        config.setCacheEnabled(false);
        config.addInterceptor(new LegacySqlCounter(statements));
        String resource = "baseline/exception-case-query.xml";
        try (var input = new ClassPathResource(resource).getInputStream()) {
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        return new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
    }

    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
    private static class LegacySqlCounter implements Interceptor {
        private final AtomicInteger statements;

        private LegacySqlCounter(AtomicInteger statements) {
            this.statements = statements;
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            statements.incrementAndGet();
            return invocation.proceed();
        }
    }

    private void assertEquivalent(Object actual, Object expected) {
        assertThat(actual).usingRecursiveComparison()
                .withComparatorForType(Comparator.comparing(OffsetDateTime::toInstant), OffsetDateTime.class)
                .isEqualTo(expected);
    }
}
