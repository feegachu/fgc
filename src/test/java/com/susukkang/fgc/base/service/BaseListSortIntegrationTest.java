package com.susukkang.fgc.base.service;

import com.susukkang.fgc.base.dto.AgentResponse;
import com.susukkang.fgc.base.dto.AgentSearchCriteria;
import com.susukkang.fgc.base.dto.InsurerResponse;
import com.susukkang.fgc.base.dto.OrganizationResponse;
import com.susukkang.fgc.base.dto.OrganizationSearchCriteria;
import com.susukkang.fgc.base.dto.ProductResponse;
import com.susukkang.fgc.base.dto.ProductSearchCriteria;
import com.susukkang.fgc.base.repository.ProductSearchImpl;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.web.PageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** #604 기준정보 4개 탭: 허용 컬럼의 양방향 정렬이 검색된 전체 데이터에 적용되고 DB 정렬과 같은지 확인한다. */
@SpringBootTest
@Transactional
class BaseListSortIntegrationTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 7, 1);

    @Autowired private OrganizationService organizations;
    @Autowired private AgentService agents;
    @Autowired private InsurerService insurers;
    @Autowired private ProductService products;
    @Autowired private JdbcTemplate jdbc;

    /**
     * @param idColumn  결과 식별 컬럼(SELECT 절)
     * @param tieOrder  동률일 때의 기존 기본 정렬(ORDER BY 뒷부분)
     * @param from      FROM·JOIN·WHERE 절
     * @param fetch     정렬 키로 첫 페이지를 가져온다
     * @param page      정렬 키와 {페이지, 크기}로 한 페이지의 항목을 가져온다
     */
    private <T> void assertSorted(String label, Map<String, String> columns, String idColumn, String tieOrder,
                                  String from, Function<String, PageResponse<T>> fetch, Function<T, Long> id,
                                  BiFunction<String, int[], List<T>> page) {
        for (var column : columns.entrySet()) {
            for (String direction : List.of("asc", "desc")) {
                List<Long> expected = jdbc.queryForList("SELECT %s %s ORDER BY %s %s NULLS LAST, %s"
                        .formatted(idColumn, from, column.getValue(), direction, tieOrder), Long.class);
                assertThat(expected.size()).as(label + " 데이터가 충분해야 한다").isGreaterThanOrEqualTo(4);
                String sort = column.getKey() + "," + direction;
                PageResponse<T> first = fetch.apply(sort);
                assertThat(first.sort()).isEqualTo(sort);
                assertThat(first.content().stream().map(id).toList())
                        .as("%s %s", label, sort).containsExactlyElementsOf(expected.subList(0, first.content().size()));
                // 현재 페이지가 아니라 검색된 전체 데이터 기준으로 정렬한다.
                assertThat(page.apply(sort, new int[] {2, 2}).stream().map(id).toList())
                        .as("%s %s page 2", label, sort)
                        .containsExactlyElementsOf(expected.stream().skip(2).limit(2).toList());
            }
        }
    }

    @Test
    void organizationsSortByAllowedColumns() {
        Map<String, String> columns = Map.of(
                "organizationCode", "o.organization_code", "organizationName", "o.organization_name",
                "parentName", "parent.organization_name", "effectiveFrom", "o.effective_from",
                "effectiveTo", "o.effective_to");
        assertThat(OrganizationServiceImpl.SORT_KEYS).containsExactlyInAnyOrderElementsOf(columns.keySet());
        assertSorted("조직", columns, "o.organization_id", "o.organization_code ASC, o.organization_id",
                "FROM fgc.organization o LEFT JOIN fgc.organization parent ON parent.organization_id = o.parent_id",
                sort -> organizations.search(new OrganizationSearchCriteria(null, AS_OF, true, sort), 1, 20),
                OrganizationResponse::organizationId,
                (sort, p) -> organizations.search(new OrganizationSearchCriteria(null, AS_OF, true, sort), p[0], p[1])
                        .content());
    }

    @Test
    void insurersSortByAllowedColumns() {
        Map<String, String> columns = Map.of("insurerCode", "i.insurer_code", "insurerName", "i.insurer_name");
        assertThat(InsurerServiceImpl.SORT_KEYS).containsExactlyInAnyOrderElementsOf(columns.keySet());
        assertSorted("보험회사", columns, "i.insurer_id", "i.insurer_code ASC, i.insurer_id", "FROM fgc.insurer i",
                sort -> insurers.search(null, sort, 1, 20), InsurerResponse::insurerId,
                (sort, p) -> insurers.search(null, sort, p[0], p[1]).content());
    }

    @Test
    void agentsSortByAllowedColumns() {
        Map<String, String> columns = Map.of(
                "agentCode", "a.agent_code", "agentName", "a.agent_name", "organizationName", "o.organization_name",
                "appointmentDate", "a.appointment_date", "terminationDate", "a.termination_date",
                "latestRegistrationDate", "a.latest_registration_date");
        assertThat(AgentServiceImpl.SORT_KEYS).containsExactlyInAnyOrderElementsOf(columns.keySet());
        assertSorted("설계사", columns, "a.agent_id", "a.agent_code ASC, a.agent_id",
                "FROM fgc.agent a JOIN fgc.organization o ON o.organization_id = a.organization_id",
                sort -> agents.search(new AgentSearchCriteria(null, null, AS_OF, true, true, sort), 1, 20),
                AgentResponse::agentId,
                (sort, p) -> agents.search(new AgentSearchCriteria(null, null, AS_OF, true, true, sort), p[0], p[1])
                        .content());
    }

    @Test
    void productsSortByAllowedColumns() {
        Long insurerId = jdbc.queryForObject("""
                SELECT insurer_id FROM fgc.product GROUP BY insurer_id ORDER BY count(*) DESC, insurer_id LIMIT 1
                """, Long.class);
        // 데모 데이터는 보험회사당 판매버전이 적어, 값이 겹치는 버전을 더 만들어 동률·페이지 이동을 함께 검증한다.
        Long productId = jdbc.queryForObject(
                "SELECT product_id FROM fgc.product WHERE insurer_id = ? ORDER BY product_id LIMIT 1", Long.class, insurerId);
        // 이 테스트는 같은 상품에 기준일이 같은 판매버전 여러 개를 일부러 만든다(롤백되므로 지연 검사는 실행되지 않는다)
        jdbc.execute("SET CONSTRAINTS fgc.ex_product_offering_sales_period DEFERRED");
        for (int i = 0; i < 6; i++) {
            jdbc.update("""
                    INSERT INTO fgc.product_offering (product_id, offering_version, sales_start_date,
                        basic_document_version, basic_document_date, channel_code, fee_regime_code)
                    VALUES (?, ?, ?, ?, ?, 'FACE_TO_FACE', 'CURRENT')
                    """, productId, "T604-" + (i % 4) + "-" + i, LocalDate.of(2090, 1 + i % 3, 1), "BD-T604-" + i,
                    LocalDate.of(2089, 1 + i % 2, 1));
        }
        Map<String, String> columns = Map.of(
                "insurerProductCode", "p.insurer_product_code", "productName", "p.product_name",
                "standardProductCode", "p.standard_product_code", "offeringVersion", "po.offering_version",
                "salesStartDate", "po.sales_start_date", "basicDocumentDate", "po.basic_document_date");
        assertThat(ProductSearchImpl.SORT_KEYS).containsExactlyInAnyOrderElementsOf(columns.keySet());
        assertSorted("상품", columns, "po.product_offering_id",
                "p.insurer_product_code, po.offering_version, po.channel_code, po.product_offering_id",
                "FROM fgc.product p JOIN fgc.product_offering po ON po.product_id = p.product_id"
                        + " WHERE p.insurer_id = " + insurerId,
                sort -> products.search(new ProductSearchCriteria(insurerId, AS_OF, true, true, sort), 1, 20),
                ProductResponse::productOfferingId,
                (sort, p) -> products.search(new ProductSearchCriteria(insurerId, AS_OF, true, true, sort), p[0], p[1])
                        .content());
    }

    @Test
    void rejectsUnsupportedSortOnEveryTab() {
        for (String bad : List.of("organizationType,asc", "insurerCode,sideways", "insurerCode", "insurerCode;DROP,asc")) {
            assertThatThrownBy(() -> organizations.search(new OrganizationSearchCriteria(null, AS_OF, true, bad), 1, 20))
                    .isInstanceOf(FgcBusinessException.class);
            assertThatThrownBy(() -> insurers.search(null, bad, 1, 20)).isInstanceOf(FgcBusinessException.class);
            assertThatThrownBy(() -> agents.search(new AgentSearchCriteria(null, null, AS_OF, true, true, bad), 1, 20))
                    .isInstanceOf(FgcBusinessException.class);
            assertThatThrownBy(() -> products.search(new ProductSearchCriteria(1, AS_OF, true, true, bad), 1, 20))
                    .isInstanceOf(FgcBusinessException.class);
        }
    }
}
