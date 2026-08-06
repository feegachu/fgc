package com.susukkang.fgc.base.mapper;

import com.susukkang.fgc.base.dto.CommissionItemResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class CommissionItemMapperIntegrationTest {

    @Autowired
    private CommissionItemMapper commissionItemMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void findsOnlyActiveCommissionItemsEffectiveOnTheGivenDate() {
        LocalDate asOf = LocalDate.of(2026, 8, 5);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String effectiveCode = "IT_EFFECTIVE_" + suffix;
        String inactiveCode = "IT_INACTIVE_" + suffix;
        String expiredCode = "IT_EXPIRED_" + suffix;

        insertItem(effectiveCode, "통합 테스트 유효 항목", true,
                LocalDate.of(2026, 1, 1), null);
        insertItem(inactiveCode, "통합 테스트 비활성 항목", false,
                LocalDate.of(2026, 1, 1), null);
        insertItem(expiredCode, "통합 테스트 만료 항목", true,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 31));

        List<CommissionItemResponse> result = commissionItemMapper.findEffectiveItems(asOf);

        assertThat(result)
                .filteredOn(item -> item.itemCode().equals(effectiveCode))
                .containsExactly(new CommissionItemResponse(
                        effectiveCode,
                        "통합 테스트 유효 항목",
                        "PAYMENT",
                        "SALES",
                        LocalDate.of(2026, 1, 1),
                        null
                ));
        assertThat(result)
                .extracting(CommissionItemResponse::itemCode)
                .doesNotContain(inactiveCode, expiredCode);
    }

    private void insertItem(
            String itemCode,
            String itemName,
            boolean active,
            LocalDate effectiveFrom,
            LocalDate effectiveTo
    ) {
        jdbcTemplate.update("""
                INSERT INTO fgc.commission_item (
                    item_code,
                    item_name,
                    cashflow_type,
                    item_category,
                    effective_from,
                    effective_to,
                    active_yn
                ) VALUES (?, ?, 'PAYMENT', 'SALES', ?, ?, ?)
                """,
                itemCode,
                itemName,
                effectiveFrom,
                effectiveTo,
                active
        );
    }
}
