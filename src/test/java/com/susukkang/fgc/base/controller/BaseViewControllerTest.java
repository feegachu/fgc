package com.susukkang.fgc.base.controller;

import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.base.dto.CommissionItemResponse;
import com.susukkang.fgc.base.service.CommissionItemService;
import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import com.susukkang.fgc.common.web.ShellAdvice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * 설명 : 기준정보 조회 화면 테스트
 *
 * @author yslee
 * @since 2026-08-11
 * @version 1.2
 */
@WebMvcTest(BaseViewController.class)
@Import({BaseViewController.class, SecurityConfig.class, ShellAdvice.class, GlobalExceptionHandler.class,
        FgcMessageResolver.class, ConstraintErrorCodeResolver.class, MessageSourceAutoConfiguration.class})
@TestPropertySource(properties = "fgc.demo-month=2026-08")
class BaseViewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CommissionItemService commissionItemService;

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "GA_ADMIN", "SETTLEMENT", "COMPLIANCE"})
    void 로그인한_전체_역할은_수수료_항목을_조회할_수_있다(String roleCode) throws Exception {
        given(commissionItemService.findEffectiveItems(LocalDate.of(2026, 8, 1)))
                .willReturn(sampleItems());

        mockMvc.perform(get("/base").with(user(principal(roleCode))))
                .andExpect(status().isOk())
                .andExpect(view().name("base/index"))
                .andExpect(model().attribute("asOf", LocalDate.of(2026, 8, 1)))
                .andExpect(model().attribute("commissionItems", sampleItems()))
                .andExpect(content().string(containsString("FGC-UI-BASE-W01")))
                .andExpect(content().string(containsString("기준정보 조회")))
                .andExpect(content().string(containsString("조직")))
                .andExpect(content().string(containsString("보험회사")))
                .andExpect(content().string(containsString("상품")))
                .andExpect(content().string(containsString("설계사")))
                .andExpect(content().string(containsString("수수료 항목")))
                .andExpect(content().string(containsString("BASE_COMMISSION")))
                .andExpect(content().string(containsString("FC 기본수수료")))
                .andExpect(content().string(containsString("2026-01-01")))
                .andExpect(content().string(not(containsString("<th scope=\"col\">산입 여부</th>"))))
                .andExpect(content().string(containsString("href=\"/base\" aria-current=\"page\"")))
                .andExpect(content().string(containsString("id=\"b1\"")))
                .andExpect(content().string(containsString("id=\"b2\"")))
                .andExpect(content().string(containsString("id=\"b3\"")))
                .andExpect(content().string(containsString("id=\"b4\"")))
                .andExpect(content().string(containsString("id=\"b5\"")))
                .andExpect(content().string(not(containsString(">등록</button>"))))
                .andExpect(content().string(not(containsString(">수정</button>"))));
    }

    @Test
    void 기준월을_바꾸면_해당_월_첫날로_유효한_항목을_조회한다() throws Exception {
        given(commissionItemService.findEffectiveItems(LocalDate.of(2026, 7, 1)))
                .willReturn(sampleItems());

        mockMvc.perform(get("/base")
                        .param("month", "2026-07")
                        .with(user(principal("SETTLEMENT"))))
                .andExpect(status().isOk())
                .andExpect(model().attribute("month", "2026-07"))
                .andExpect(model().attribute("asOf", LocalDate.of(2026, 7, 1)));

        verify(commissionItemService).findEffectiveItems(LocalDate.of(2026, 7, 1));
    }

    @Test
    void 잘못된_기준월은_설정된_기본월로_복구한다() throws Exception {
        given(commissionItemService.findEffectiveItems(LocalDate.of(2026, 8, 1)))
                .willReturn(sampleItems());

        mockMvc.perform(get("/base")
                        .param("month", "2026-13")
                        .with(user(principal("SETTLEMENT"))))
                .andExpect(status().isOk())
                .andExpect(model().attribute("month", "2026-08"))
                .andExpect(model().attribute("asOf", LocalDate.of(2026, 8, 1)));

        verify(commissionItemService).findEffectiveItems(LocalDate.of(2026, 8, 1));
    }

    @Test
    void 조회_결과가_없으면_빈_목록_안내를_표시한다() throws Exception {
        given(commissionItemService.findEffectiveItems(LocalDate.of(2026, 8, 1)))
                .willReturn(List.of());

        mockMvc.perform(get("/base").with(user(principal("SETTLEMENT"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("조건에 맞는 수수료 항목이 없습니다.")));
    }

    @Test
    void 로그인하지_않으면_로그인_화면으로_이동한다() throws Exception {
        mockMvc.perform(get("/base"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    private static List<CommissionItemResponse> sampleItems() {
        return List.of(new CommissionItemResponse(
                "BASE_COMMISSION",
                "FC 기본수수료",
                "PAYMENT",
                "SALES",
                LocalDate.of(2026, 1, 1),
                null
        ));
    }

    private static FgcUserDetails principal(String roleCode) {
        AppUserView view = new AppUserView();
        view.setUserId(1L);
        view.setLoginId("base-viewer");
        view.setPasswordHash("{noop}x");
        view.setUserName("기준정보 조회자");
        view.setRoleCode(roleCode);
        view.setAccountStatus("ACTIVE");
        return new FgcUserDetails(view, true, true);
    }
}
