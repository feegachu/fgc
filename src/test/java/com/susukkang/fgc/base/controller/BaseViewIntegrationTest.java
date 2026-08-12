package com.susukkang.fgc.base.controller;

import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 설명 : 기준정보 조회 화면 DB 연동 통합 테스트
 *
 * @author yslee
 * @since 2026-08-11
 * @version 1.2
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BaseViewIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void DB의_유효한_수수료_항목을_BASE_W01에_표시한다() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String itemCode = "VIEW_ITEM_" + suffix;
        String itemName = "화면 통합 테스트 항목 " + suffix;
        insertCommissionItem(itemCode, itemName);

        mockMvc.perform(get("/base")
                        .param("month", "2026-08")
                        .with(user(settlementPrincipal())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(itemCode)))
                .andExpect(content().string(containsString(itemName)))
                .andExpect(content().string(containsString("2026-08-01")));
    }

    private void insertCommissionItem(String itemCode, String itemName) {
        jdbcTemplate.update("""
                INSERT INTO fgc.commission_item (
                    item_code,
                    item_name,
                    cashflow_type,
                    item_category,
                    effective_from,
                    effective_to,
                    active_yn
                ) VALUES (?, ?, 'PAYMENT', 'SALES', ?, NULL, TRUE)
                """,
                itemCode,
                itemName,
                LocalDate.of(2026, 1, 1)
        );
    }

    private static FgcUserDetails settlementPrincipal() {
        AppUserView view = new AppUserView();
        view.setUserId(1L);
        view.setLoginId("settle01");
        view.setPasswordHash("{noop}x");
        view.setUserName("정산담당자");
        view.setRoleCode("SETTLEMENT");
        view.setAccountStatus("ACTIVE");
        return new FgcUserDetails(view, true, true);
    }
}
