package com.susukkang.fgc.validation.repository;

import com.susukkang.fgc.common.web.ListSort;
import com.susukkang.fgc.validation.dto.ValidationRunListRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** #604 월 통합검증 실행 목록: 허용 컬럼의 양방향 정렬을 DB 의 같은 정렬과 비교한다. */
@SpringBootTest
@Transactional
class ValidationRunSortIntegrationTest {
    @Autowired private ValidationRunRepository repository;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void sortsByEachAllowedColumnInBothDirectionsAcrossPages() {
        List<Long> users = jdbc.queryForList("SELECT user_id FROM fgc.app_user ORDER BY user_id LIMIT 2", Long.class);
        // 고정 월(2098-01~03)은 ChangedContractArbitrageIntegrationTest 가 무작위 월(2040~2099)로 커밋하고 못 지우는
        // 실행과 uq_validation_run 에서 충돌했다(PR #690 CI). 실행 시점의 최댓값 다음 해를 쓴다.
        LocalDate base = jdbc.queryForObject(
                "SELECT COALESCE(MAX(validation_month), DATE '2026-01-01') FROM fgc.validation_run", LocalDate.class)
                .plusYears(1).withDayOfYear(1);
        // 같은 달에 회차 여러 개, 실행자·시각이 같은 행과 비어 있는 행을 섞는다.
        for (int i = 0; i < 8; i++) {
            jdbc.update("""
                    INSERT INTO fgc.validation_run (validation_month, run_no, run_type, status, triggered_by, started_at)
                    VALUES (?, ?, 'PRE_CONFIRM', 'CREATED', ?, ?::timestamptz)
                    """, base.plusMonths(i % 3), 1 + i / 3, i % 3 == 0 ? null : users.get(i % users.size()),
                    i % 4 == 0 ? null : "2098-01-0" + (1 + i % 3) + "T09:00:00+09:00");
        }
        Map<String, String> columns = Map.of(
                "runNo", "v.run_no", "triggeredBy", "triggered.login_id", "startedAt", "v.started_at",
                "completedAt", "v.completed_at", "finalizedAt", "v.finalized_at");
        assertThat(ValidationRunSearchImpl.SORT_KEYS)
                .containsExactlyInAnyOrderElementsOf(List.of("validationMonth", "runNo", "triggeredBy", "startedAt",
                        "completedAt", "finalizedAt"));
        for (var column : columns.entrySet()) {
            for (String direction : List.of("ASC", "DESC")) {
                List<Long> expected = jdbc.queryForList("""
                        SELECT v.validation_run_id FROM fgc.validation_run v
                          LEFT JOIN fgc.app_user triggered ON triggered.user_id = v.triggered_by
                         ORDER BY %s %s NULLS LAST, v.validation_month DESC, v.run_no DESC
                        """.formatted(column.getValue(), direction), Long.class);
                assertSorted(new ListSort(column.getKey(), "ASC".equals(direction)), expected, column.getKey() + " " + direction);
            }
        }
        // 검증월 정렬은 같은 달 안에서 회차도 같은 방향으로 따라간다(기존 기본 정렬: 검증월·회차 내림차순).
        for (String direction : List.of("ASC", "DESC")) {
            List<Long> expected = jdbc.queryForList(
                    "SELECT validation_run_id FROM fgc.validation_run ORDER BY validation_month %s, run_no %s"
                            .formatted(direction, direction), Long.class);
            assertSorted(new ListSort("validationMonth", "ASC".equals(direction)), expected, "validationMonth " + direction);
        }
        assertThat(ValidationRunSearchImpl.sortOf(null)).isEqualTo(ValidationRunSearchImpl.DEFAULT_SORT);
    }

    private void assertSorted(ListSort sort, List<Long> expected, String label) {
        assertThat(repository.searchSorted(null, null, sort, PageRequest.of(0, 1000)).getContent())
                .extracting(ValidationRunListRow::getValidationRunId).as(label).containsExactlyElementsOf(expected);
        // 현재 페이지가 아니라 검색된 전체 데이터 기준으로 정렬한다.
        var second = repository.searchSorted(null, null, sort, PageRequest.of(1, 3));
        assertThat(second.getTotalElements()).isEqualTo(expected.size());
        assertThat(second.getContent()).extracting(ValidationRunListRow::getValidationRunId)
                .as(label + " page 2").containsExactlyElementsOf(expected.stream().skip(3).limit(3).toList());
    }
}
