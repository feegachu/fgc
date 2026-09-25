package com.susukkang.fgc.base.repository;

import com.susukkang.fgc.base.code.InsurerType;
import com.susukkang.fgc.base.entity.Insurer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : Flyway 스키마와 보험사 JPA 조회 매핑을 PostgreSQL에서 검증한다.
 *
 * @author hyJune
 * @version 1.0
 * @since 2026-09-24
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class InsurerRepositoryIntegrationTest {

    @Autowired
    private InsurerRepository insurerRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @ParameterizedTest
    @CsvSource({"LIFE, true", "NON_LIFE, false"})
    void findByIdMapsAllColumns(InsurerType insurerType, boolean activeYn) {
        String code = uniqueCode();
        String name = "JPA 조회 테스트 보험사";
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-09-23T10:20:30.123456+09:00");
        OffsetDateTime updatedAt = OffsetDateTime.parse("2026-09-24T14:30:40.654321+09:00");

        // JPA로 저장한 객체가 아닌, 실제 DB 행을 조회하도록 JDBC로 데이터를 준비한다.
        Long insurerId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.insurer (
                    insurer_code, insurer_name, insurer_type, active_yn, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                RETURNING insurer_id
                """, Long.class, code, name, insurerType.name(), activeYn, createdAt, updatedAt);

        Insurer insurer = insurerRepository.findById(insurerId).orElseThrow();

        assertThat(insurer.getInsurerId()).isEqualTo(insurerId);
        assertThat(insurer.getInsurerCode()).isEqualTo(code);
        assertThat(insurer.getInsurerName()).isEqualTo(name);
        assertThat(insurer.getInsurerType()).isEqualTo(insurerType);
        assertThat(insurer.isActiveYn()).isEqualTo(activeYn);
        // timestamptz는 조회 시 UTC로 정규화될 수 있으므로 같은 시점인지 비교한다.
        assertThat(insurer.getCreatedAt().toInstant()).isEqualTo(createdAt.toInstant());
        assertThat(insurer.getUpdatedAt().toInstant()).isEqualTo(updatedAt.toInstant());
    }

    @Test
    void readsDatabaseDefaultsForActiveYnAndTimestamps() {
        Long insurerId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.insurer (insurer_code, insurer_name, insurer_type)
                VALUES (?, ?, ?)
                RETURNING insurer_id
                """, Long.class, uniqueCode(), "JPA 기본값 테스트 보험사", "LIFE");

        Insurer insurer = insurerRepository.findById(insurerId).orElseThrow();

        assertThat(insurer.isActiveYn()).isTrue();
        assertThat(insurer.getCreatedAt()).isNotNull();
        assertThat(insurer.getUpdatedAt()).isNotNull();
    }

    @Test
    void searchIncludesActiveAndInactiveInsurers() {
        String prefix = uniqueCode();
        insertInsurer(prefix + "-I", "중지된 손해보험사", "NON_LIFE", false);
        insertInsurer(prefix + "-A", "활성 생명보험사", "LIFE", true);

        Page<Insurer> result = insurerRepository.search(prefix, pageRequest(0, 20));

        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).extracting(Insurer::getInsurerCode)
                .containsExactly(prefix + "-A", prefix + "-I");
        assertThat(result.getContent()).extracting(Insurer::isActiveYn)
                .containsExactly(true, false);
        assertThat(result.getContent()).extracting(Insurer::getInsurerType)
                .containsExactly(InsurerType.LIFE, InsurerType.NON_LIFE);
    }

    @Test
    void searchMatchesCodeIgnoringCaseAndKeepsSortingPagingAndFilteredCount() {
        String prefix = uniqueCode().toLowerCase(Locale.ROOT);
        for (int index = 21; index >= 1; index--) {
            insertInsurer("%s-%02d".formatted(prefix, index), "보험회사 " + index, "LIFE", true);
        }
        insertInsurer(uniqueCode(), "검색에서 제외되는 보험회사", "NON_LIFE", true);
        String keyword = prefix.toUpperCase(Locale.ROOT);

        // 첫 페이지를 가득 채워 검색 조건이 적용된 COUNT 쿼리도 검증한다.
        Page<Insurer> firstPage = insurerRepository.search(keyword, pageRequest(0, 20));
        Page<Insurer> secondPage = insurerRepository.search(keyword, pageRequest(1, 20));
        Page<Insurer> beyondLastPage = insurerRepository.search(keyword, pageRequest(2, 20));

        List<String> expectedFirstPageCodes = IntStream.rangeClosed(1, 20)
                .mapToObj(index -> "%s-%02d".formatted(prefix, index))
                .toList();
        assertThat(firstPage.getContent()).extracting(Insurer::getInsurerCode)
                .containsExactlyElementsOf(expectedFirstPageCodes);
        assertThat(firstPage.getTotalElements()).isEqualTo(21);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(secondPage.getContent()).singleElement()
                .extracting(Insurer::getInsurerCode)
                .isEqualTo(prefix + "-21");
        assertThat(secondPage.getTotalElements()).isEqualTo(21);
        assertThat(secondPage.getTotalPages()).isEqualTo(2);
        assertThat(beyondLastPage.getContent()).isEmpty();
        assertThat(beyondLastPage.getTotalElements()).isEqualTo(21);
        assertThat(beyondLastPage.getTotalPages()).isEqualTo(2);
    }

    @Test
    void searchMatchesPartOfNameIgnoringCase() {
        String nameKeyword = "미래Life-" + UUID.randomUUID().toString().substring(0, 8);
        String code = uniqueCode();
        insertInsurer(code, "가상 " + nameKeyword + " 보험회사", "LIFE", true);
        insertInsurer(uniqueCode(), "검색에서 제외되는 보험회사", "LIFE", true);

        Page<Insurer> result = insurerRepository.search(
                nameKeyword.toUpperCase(Locale.ROOT), pageRequest(0, 20));

        assertThat(result.getContent()).singleElement()
                .extracting(Insurer::getInsurerCode)
                .isEqualTo(code);
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void searchWithNullKeywordReturnsAllInsurers() {
        insertInsurer(uniqueCode(), "전체 조회 활성 보험회사", "LIFE", true);
        insertInsurer(uniqueCode(), "전체 조회 중지 보험회사", "NON_LIFE", false);
        // 데모 데이터 개수를 고정하지 않고 현재 DB 전체와 결과를 비교한다.
        List<Long> expectedIds = jdbcTemplate.queryForList(
                "SELECT insurer_id FROM fgc.insurer ORDER BY insurer_code", Long.class);

        Page<Insurer> result = insurerRepository.search(null, pageRequest(0, expectedIds.size()));

        assertThat(result.getContent()).extracting(Insurer::getInsurerId)
                .containsExactlyElementsOf(expectedIds);
        assertThat(result.getTotalElements()).isEqualTo(expectedIds.size());
        assertThat(result.getTotalPages()).isEqualTo(1);
    }

    @Test
    void searchReturnsEmptyPageWhenNothingMatches() {
        Page<Insurer> result = insurerRepository.search(
                "no-match-" + UUID.randomUUID(), pageRequest(0, 20));

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        assertThat(result.getTotalPages()).isZero();
    }

    private void insertInsurer(String code, String name, String type, boolean activeYn) {
        jdbcTemplate.update("""
                INSERT INTO fgc.insurer (insurer_code, insurer_name, insurer_type, active_yn)
                VALUES (?, ?, ?, ?)
                """, code, name, type, activeYn);
    }

    private PageRequest pageRequest(int page, int size) {
        return PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "insurerCode"));
    }

    private String uniqueCode() {
        return "JPA-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
