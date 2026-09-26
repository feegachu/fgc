package com.susukkang.fgc.base.repository;

import com.susukkang.fgc.base.dto.CommissionItemResponse;
import com.susukkang.fgc.base.entity.CommissionItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : 실제 PostgreSQL과 Flyway 스키마에서 수수료 항목의 JPA 조회 계약을 검증한다.
 * 테스트 데이터는 각 테스트가 끝날 때 롤백된다.
 *
 * @author hjKang
 * @version 1.1
 * @since 2026-09-26
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CommissionItemRepositoryIntegrationTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 5);

    @Autowired
    private CommissionItemRepository commissionItemRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @ParameterizedTest(name = "{0}: 엔티티와 응답 DTO의 모든 조회 필드 매핑")
    @CsvSource({"PAYMENT, SALES", "DEDUCTION, CLAWBACK"})
    void mapsEntityAndResponseForBothCashflowTypes(String cashflowType, String itemCategory) {
        String code = uniqueCode();
        LocalDate from = AS_OF.minusMonths(1);
        LocalDate to = AS_OF.plusMonths(1);
        // JPA의 저장 객체를 재사용하지 않도록 JDBC로 실제 DB 행을 준비한다.
        long id = insertItem(code, "JPA 매핑 테스트 항목", cashflowType, itemCategory, true, from, to);

        CommissionItem entity = commissionItemRepository.findById(id).orElseThrow();
        List<CommissionItemResponse> result = commissionItemRepository.findEffectiveItems(AS_OF);

        assertThat(entity.getCommissionItemId()).isEqualTo(id);
        assertThat(entity.getItemCode()).isEqualTo(code);
        assertThat(entity.getItemName()).isEqualTo("JPA 매핑 테스트 항목");
        assertThat(entity.getCashflowType()).isEqualTo(cashflowType);
        assertThat(entity.getItemCategory()).isEqualTo(itemCategory);
        assertThat(entity.getEffectiveFrom()).isEqualTo(from);
        assertThat(entity.getEffectiveTo()).isEqualTo(to);
        assertThat(entity.isActiveYn()).isTrue();
        assertThat(result).filteredOn(item -> item.itemCode().equals(code))
                .containsExactly(new CommissionItemResponse(
                        id, code, "JPA 매핑 테스트 항목", cashflowType, itemCategory, from, to));
    }

    @Test
    @DisplayName("시작일·종료일을 포함하고 비활성·미래·만료 항목을 제외한다")
    void includesPeriodBoundariesAndExcludesInactiveFutureAndExpiredItems() {
        String prefix = uniqueCode();
        long inactiveId = insertItem(prefix + "-INACTIVE", "비활성 항목", "PAYMENT", "SALES",
                false, AS_OF.minusMonths(1), null);
        insertItem(prefix + "-FUTURE", "미래 항목", "PAYMENT", "SALES",
                true, AS_OF.plusDays(1), null);
        insertItem(prefix + "-EXPIRED", "만료 항목", "PAYMENT", "SALES",
                true, AS_OF.minusMonths(1), AS_OF.minusDays(1));
        insertItem(prefix + "-START", "시작일인 항목", "PAYMENT", "SALES",
                true, AS_OF, AS_OF.plusMonths(1));
        insertItem(prefix + "-END", "종료일인 항목", "DEDUCTION", "CLAWBACK",
                true, AS_OF.minusMonths(1), AS_OF);
        insertItem(prefix + "-ONE-DAY", "하루만 유효한 항목", "PAYMENT", "SALES",
                true, AS_OF, AS_OF);
        insertItem(prefix + "-OPEN", "종료일 없는 항목", "PAYMENT", "SALES",
                true, AS_OF.minusMonths(1), null);

        List<CommissionItemResponse> result = commissionItemRepository.findEffectiveItems(AS_OF);

        assertThat(result).filteredOn(item -> item.itemCode().startsWith(prefix))
                .extracting(CommissionItemResponse::itemCode)
                .containsExactly(prefix + "-END", prefix + "-ONE-DAY", prefix + "-OPEN", prefix + "-START");
        assertThat(commissionItemRepository.findById(inactiveId).orElseThrow().isActiveYn()).isFalse();
    }

    @Test
    @DisplayName("수수료 항목을 삽입 순서와 무관하게 코드 오름차순으로 조회한다")
    void sortsByItemCodeInsteadOfInsertionOrder() {
        String prefix = uniqueCode();
        for (String suffix : List.of("C", "B", "A")) {
            insertItem(prefix + "-" + suffix, "정렬 테스트 항목", "PAYMENT", "SALES",
                    true, AS_OF.minusMonths(1), null);
        }

        List<CommissionItemResponse> result = commissionItemRepository.findEffectiveItems(AS_OF);

        assertThat(result).filteredOn(item -> item.itemCode().startsWith(prefix))
                .extracting(CommissionItemResponse::itemCode)
                .containsExactly(prefix + "-A", prefix + "-B", prefix + "-C");
    }

    @Test
    @DisplayName("DB 기본 활성값과 null 종료일을 읽고 유효 항목으로 반환한다")
    void readsDatabaseDefaultsAndIncludesOpenEndedItem() {
        String code = uniqueCode();
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.commission_item (
                    item_code, item_name, cashflow_type, item_category, effective_from
                ) VALUES (?, ?, 'PAYMENT', 'SALES', ?)
                RETURNING commission_item_id
                """, Long.class, code, "DB 기본값 테스트 항목", AS_OF);

        CommissionItem entity = commissionItemRepository.findById(id).orElseThrow();
        List<CommissionItemResponse> result = commissionItemRepository.findEffectiveItems(AS_OF);

        assertThat(entity.isActiveYn()).isTrue();
        assertThat(entity.getEffectiveTo()).isNull();
        assertThat(result).filteredOn(item -> item.itemCode().equals(code))
                .containsExactly(new CommissionItemResponse(
                        id, code, "DB 기본값 테스트 항목", "PAYMENT", "SALES", AS_OF, null));
    }

    @Test
    @DisplayName("기준일에 유효한 항목이 없으면 빈 목록을 반환한다")
    void returnsEmptyListWhenNothingIsEffective() {
        insertItem(uniqueCode(), "미래 항목", "PAYMENT", "SALES", true, AS_OF, null);
        // Flyway 초기 데이터의 날짜가 바뀌어도 모든 항목의 시작일 이전을 조회한다.
        LocalDate earliestStart = jdbcTemplate.queryForObject(
                "SELECT MIN(effective_from) FROM fgc.commission_item", LocalDate.class);

        assertThat(commissionItemRepository.findEffectiveItems(earliestStart.minusDays(1))).isEmpty();
    }

    private long insertItem(String code, String name, String cashflowType, String itemCategory,
                            boolean active, LocalDate effectiveFrom, LocalDate effectiveTo) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.commission_item (
                    item_code, item_name, cashflow_type, item_category,
                    active_yn, effective_from, effective_to
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING commission_item_id
                """, Long.class, code, name, cashflowType, itemCategory, active, effectiveFrom, effectiveTo);
    }

    private String uniqueCode() {
        return "JPA-CI-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
