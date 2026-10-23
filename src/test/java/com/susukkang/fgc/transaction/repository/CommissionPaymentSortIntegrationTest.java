package com.susukkang.fgc.transaction.repository;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.transaction.dto.CommissionPaymentListResponse;
import com.susukkang.fgc.transaction.dto.CommissionPaymentSearchCondition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** #604 수수료 지급 건 목록: 허용 컬럼의 양방향 정렬을 DB 의 같은 정렬과 비교한다. */
@SpringBootTest
@Transactional
class CommissionPaymentSortIntegrationTest {
    @Autowired private CommissionPaymentQueryRepository repository;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void sortsByEachAllowedColumnInBothDirectionsAcrossPages() {
        Map<String, String> columns = Map.of(
                "key", "ct.source_business_key", "month", "ct.settlement_month", "agent", "agent.agent_name",
                "item", "item.item_name", "amount", "ct.amount",
                "attributions", "COUNT(attribution.transaction_attribution_id)");
        assertThat(CommissionPaymentQueryRepository.SORT_KEYS).containsExactlyInAnyOrderElementsOf(columns.keySet());
        for (var column : columns.entrySet()) {
            for (String direction : List.of("asc", "desc")) {
                CommissionPaymentSearchCondition condition = new CommissionPaymentSearchCondition();
                condition.setSort(column.getKey() + "," + direction);
                List<Long> expected = jdbc.queryForList("""
                        SELECT ct.commission_transaction_id
                          FROM fgc.commission_transaction ct
                          JOIN fgc.commission_item item ON item.commission_item_id = ct.commission_item_id
                          LEFT JOIN fgc.agent agent ON agent.agent_id = ct.recipient_agent_id
                          LEFT JOIN fgc.transaction_attribution attribution
                            ON attribution.commission_transaction_id = ct.commission_transaction_id
                         GROUP BY ct.commission_transaction_id, agent.agent_name, item.item_code, item.item_name
                         ORDER BY %s %s NULLS LAST, ct.commission_transaction_id DESC
                        """.formatted(column.getValue(), direction), Long.class);
                assertThat(expected).hasSizeGreaterThan(5);
                assertThat(repository.selectByCondition(condition, 100, 0))
                        .extracting(CommissionPaymentListResponse::getCommissionTransactionId)
                        .as("%s %s", column.getKey(), direction).containsExactlyElementsOf(expected);
                // 현재 페이지가 아니라 검색된 전체 데이터 기준으로 정렬한다.
                assertThat(repository.selectByCondition(condition, 3, 3))
                        .extracting(CommissionPaymentListResponse::getCommissionTransactionId)
                        .as("%s %s page 2", column.getKey(), direction)
                        .containsExactlyElementsOf(expected.stream().skip(3).limit(3).toList());
            }
        }
    }

    @Test
    void rejectsUnsupportedSort() {
        for (String sort : List.of("status,asc", "amount,sideways", "amount", "amount;DROP,asc")) {
            CommissionPaymentSearchCondition condition = new CommissionPaymentSearchCondition();
            condition.setSort(sort);
            assertThatThrownBy(() -> repository.selectByCondition(condition, 20, 0))
                    .isInstanceOf(FgcBusinessException.class);
        }
    }
}
