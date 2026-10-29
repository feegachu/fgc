package com.susukkang.fgc.base.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(roles = "SETTLEMENT")
class SearchSelectionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void sixHundredthAgentCanBeFoundByNameCodeAndSelectedId() throws Exception {
        jdbc.update("""
            INSERT INTO fgc.agent(agent_code, agent_name, rank_code, organization_id, appointment_date, agent_status, active_yn)
            SELECT 'S633-' || lpad(n::text, 4, '0'), 'S633 설계사' || n, 'FC',
              (SELECT min(organization_id) FROM fgc.organization), DATE '2026-01-01', 'ACTIVE', true
            FROM generate_series(1,600) n
            """);
        for (String keyword : new String[]{"S633 설계사600", "S633-0600"}) {
            mvc.perform(get("/api/v1/base/agents").param("asOf", "2026-07-01").param("keyword", keyword).param("size", "20"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].agentCode").value("S633-0600"));
        }
        Long selected = jdbc.queryForObject("SELECT agent_id FROM fgc.agent WHERE agent_code = 'S633-0600'", Long.class);
        mvc.perform(get("/api/v1/base/agents").param("asOf", "2026-07-01").param("selectedId", selected.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].agentId").value(selected));
        jdbc.update("UPDATE fgc.agent SET termination_date=DATE '2026-06-01', agent_status='TERMINATED' WHERE agent_id=?", selected);
        mvc.perform(get("/api/v1/base/agents").param("asOf", "2026-07-01").param("selectedId", selected.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void itemSearchPaginatesOnServerAndPreservesLegacyArray() throws Exception {
        jdbc.update("""
            INSERT INTO fgc.commission_item(item_code,item_name,cashflow_type,item_category,effective_from,active_yn)
            SELECT 'S633-' || lpad(n::text,4,'0'), '검색항목' || n, 'PAYMENT','SALES', DATE '2026-01-01', true
            FROM generate_series(1,25) n
            """);
        mvc.perform(get("/api/v1/base/commission-items/search").param("asOf","2026-07-01")
            .param("keyword","검색항목").param("page","2").param("size","20"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(25))
            .andExpect(jsonPath("$.data.content.length()").value(5))
            .andExpect(jsonPath("$.data.content[0].itemCode").value("S633-0021"));
        jdbc.update("""
            INSERT INTO fgc.commission_item(item_code,item_name,cashflow_type,item_category,effective_from,active_yn)
            VALUES ('S633-DEDUCTION', '검색항목 차감', 'DEDUCTION', 'SALES', DATE '2026-01-01', true)
            """);
        mvc.perform(get("/api/v1/base/commission-items/search").param("asOf","2026-07-01")
            .param("keyword","검색항목").param("cashflowType","DEDUCTION"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].itemCode").value("S633-DEDUCTION"))
            .andExpect(jsonPath("$.data.content[0].cashflowType").value("DEDUCTION"));
        mvc.perform(get("/api/v1/base/commission-items").param("asOf","2026-07-01"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());
        mvc.perform(get("/api/v1/base/commission-items/search").param("asOf","2026-07-01").param("size","101"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void productSearchAndCountUseTheSameLiteralKeywordAndDateFilters() throws Exception {
        jdbc.update("""
            INSERT INTO fgc.product(insurer_id,insurer_product_code,standard_product_code,product_name,product_group_code)
            SELECT (SELECT insurer_id FROM fgc.product ORDER BY product_id LIMIT 1),
                'S633-P-' || lpad(n::text,4,'0'), 'S633-STD-' || n, '검색상품' || n, 'LIFE'
            FROM generate_series(1,25) n
            """);
        jdbc.update("""
            INSERT INTO fgc.product_offering(product_id,offering_version,sales_start_date,basic_document_version,
                basic_document_date,channel_code,fee_regime_code,active_yn)
            SELECT product_id, 'S633-version-' || right(insurer_product_code,4),
                DATE '2026-01-01', 'S633', DATE '2026-01-01', 'GA', 'LEGACY', true
            FROM fgc.product WHERE insurer_product_code LIKE 'S633-P-%'
            """);
        Long insurer = jdbc.queryForObject("SELECT insurer_id FROM fgc.product ORDER BY product_id LIMIT 1", Long.class);
        String name = jdbc.queryForObject("SELECT product_name FROM fgc.product WHERE insurer_id=? ORDER BY product_id LIMIT 1", String.class, insurer);
        mvc.perform(get("/api/v1/base/products").param("asOf","2026-07-01").param("insurerId",insurer.toString())
            .param("keyword",name).param("size","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.content.length()").value(1));
        mvc.perform(get("/api/v1/base/products").param("asOf","2026-07-01").param("insurerId",insurer.toString())
            .param("keyword","%not-a-wildcard_"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(get("/api/v1/base/products").param("asOf","2026-07-01").param("insurerId",insurer.toString())
            .param("keyword","S633-version-").param("page","2").param("size","20"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(25))
            .andExpect(jsonPath("$.data.content.length()").value(5))
            .andExpect(jsonPath("$.data.content[0].offeringVersion").value("S633-version-0021"));
    }
}
