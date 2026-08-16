package com.susukkang.fgc.exceptioncase.mapper;

import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.dashboard.mapper.DashboardMapper;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseListRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #83: EXCP-W01 예외함 목록 조회 — 화면 필터 3가지 경로(OPEN / 개별 상태값 / 필터 없음)가
 * ExceptionStatus.dbStatuses() 로 풀려 실제 SQL 에서 맞게 걸리는지, 그리고 미처리 건수가
 * 대시보드 KPI(DashboardMapper.countOpenException)와 같은 기준인지 지킨다.
 */
@SpringBootTest
@Transactional
class ExceptionCaseQueryMapperIntegrationTest {

    @Autowired
    private ExceptionCaseQueryMapper mapper;

    @Autowired
    private DashboardMapper dashboardMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String suffix;

    @BeforeEach
    void insertFixtures() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        insertCase("NEW", "CRITICAL", "IT 신규-" + suffix);
        insertCase("IN_REVIEW", "WARNING", "IT 검토중-" + suffix);
        insertCase("RESOLVED", "INFO", "IT 해결-" + suffix);
    }

    private void insertCase(String status, String severity, String title) {
        jdbcTemplate.update("""
                INSERT INTO fgc.exception_case
                       (exception_key, exception_type, severity, status,
                        source_entity_type, source_entity_id, title)
                VALUES (?, 'DATA_QUALITY', ?, ?, 'IT', ?, ?)
                """, "IT:" + suffix + ":" + status, severity, status, suffix, title);
    }

    private List<String> myTitles(List<ExceptionCaseListRow> rows) {
        return rows.stream().map(ExceptionCaseListRow::title)
                .filter(t -> t.endsWith(suffix)).toList();
    }

    @Test
    void OPEN_필터는_신규와_검토중만_조회한다() {
        var rows = mapper.findCases(ExceptionStatus.dbStatuses("OPEN"));
        assertThat(myTitles(rows)).containsExactlyInAnyOrder("IT 신규-" + suffix, "IT 검토중-" + suffix);
    }

    @Test
    void 실제_상태코드_필터는_그_상태만_조회한다() {
        var rows = mapper.findCases(ExceptionStatus.dbStatuses("RESOLVED"));
        assertThat(myTitles(rows)).containsExactly("IT 해결-" + suffix);
    }

    @Test
    void 필터_없음은_전체를_조회한다() {
        var rows = mapper.findCases(ExceptionStatus.dbStatuses(""));
        assertThat(myTitles(rows)).hasSize(3);
    }

    /** FUN-057: 대시보드 '미처리 예외' 카드 건수와 예외함 미처리 건수는 같은 기준이어야 한다. */
    @Test
    void 미처리_건수는_대시보드_KPI_집계와_일치한다() {
        long screen = mapper.countByStatuses(ExceptionStatus.dbStatuses(ExceptionStatus.OPEN_FILTER));
        assertThat(screen).isEqualTo(dashboardMapper.countOpenException());
    }
}
