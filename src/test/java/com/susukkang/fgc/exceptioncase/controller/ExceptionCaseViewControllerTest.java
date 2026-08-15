package com.susukkang.fgc.exceptioncase.controller;

import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import com.susukkang.fgc.common.web.ShellAdvice;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseListRow;
import com.susukkang.fgc.exceptioncase.mapper.ExceptionCaseQueryMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static com.susukkang.fgc.common.code.ExceptionStatus.IN_REVIEW;
import static com.susukkang.fgc.common.code.ExceptionStatus.NEW;
import static com.susukkang.fgc.common.code.ExceptionStatus.RESOLVED;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * EXCP-W01 예외함 — 화면 필터 status=OPEN(묶음값)을 서버가 NEW+IN_REVIEW 로
 * 풀어서 조회하는지(#83) 를 OPEN / 개별 상태값 / 필터 없음 3가지 경로로 지킨다.
 * FUN-057 인수조건 "카드 클릭 → 목록 이동 + 검색조건 자동 적용"(화면정의서 :405)의 수신부.
 */
@WebMvcTest(ExceptionCaseViewController.class)
@Import({ExceptionCaseViewController.class, ShellAdvice.class, GlobalExceptionHandler.class,
        FgcMessageResolver.class, ConstraintErrorCodeResolver.class, MessageSourceAutoConfiguration.class})
@TestPropertySource(properties = "fgc.demo-month=2026-07")
class ExceptionCaseViewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExceptionCaseQueryMapper mapper;

    private static FgcUserDetails principal(String loginId, String userName, String roleCode) {
        AppUserView view = new AppUserView();
        view.setUserId(1L);
        view.setLoginId(loginId);
        view.setPasswordHash("{noop}x");
        view.setUserName(userName);
        view.setRoleCode(roleCode);
        view.setAccountStatus("ACTIVE");
        return new FgcUserDetails(view, true, true);
    }

    private static final FgcUserDetails SETTLE = principal("settle01", "정산담당", "SETTLEMENT");

    private static final ExceptionCaseListRow OPEN_ROW = new ExceptionCaseListRow(
            10L, "CAP_VIOLATION", "CRITICAL", "C001", "1200% 한도 초과", "NEW", null,
            OffsetDateTime.parse("2026-07-10T09:00:00+09:00"), "COMMISSION_TRANSACTION", "77");

    private static final ExceptionCaseListRow RESOLVED_ROW = new ExceptionCaseListRow(
            11L, "DATA_QUALITY", "WARNING", null, "필수값 누락", "RESOLVED", "settle01",
            OffsetDateTime.parse("2026-07-01T09:00:00+09:00"), "INSURANCE_CONTRACT", "5");

    /** 원천 화면 라우트가 아직 없는 유형 — 참조 컬럼이 링크 없이 텍스트로만 나와야 한다. */
    private static final ExceptionCaseListRow UNKNOWN_SOURCE_ROW = new ExceptionCaseListRow(
            12L, "OTHER", "INFO", null, "원천 미상 예외", "NEW", null,
            OffsetDateTime.parse("2026-07-02T09:00:00+09:00"), "LEGACY_SOURCE", "9");

    @Test
    void 필터_없이_열면_기본값_OPEN이_NEW와_IN_REVIEW로_풀려_조회된다() throws Exception {
        given(mapper.findCases(List.of(NEW, IN_REVIEW))).willReturn(List.of(OPEN_ROW));
        given(mapper.countByStatuses(List.of(NEW, IN_REVIEW))).willReturn(1L);

        mockMvc.perform(get("/exceptions").with(user(SETTLE)))
                .andExpect(status().isOk())
                .andExpect(view().name("exception/list"))
                .andExpect(model().attribute("statusFilter", "OPEN"))
                // 셸 + 화면 ID + 목록 실데이터 렌더링
                .andExpect(content().string(containsString("FGC-UI-EXCP-W01")))
                .andExpect(content().string(containsString("1200% 한도 초과")))
                .andExpect(content().string(containsString("미배정")))
                // 참조 컬럼은 원천 화면 링크 (화면정의서 :1349 "만들 때 주의")
                .andExpect(content().string(containsString("href=\"/transactions\"")))
                .andExpect(content().string(containsString("COMMISSION_TRANSACTION:77")))
                // 미처리 배너 건수 — 대시보드 KPI(countOpenException)와 같은 기준
                .andExpect(content().string(containsString("건이 처리를 기다립니다")))
                // 상태 select 는 OPEN 이 선택된 채로 돌아온다
                .andExpect(content().string(containsString("<option value=\"OPEN\" selected=\"selected\">")));

        verify(mapper).findCases(List.of(NEW, IN_REVIEW));
    }

    /** 대시보드 '미처리 예외' KPI 카드 링크(@{/exceptions(status='OPEN',month=...)}) 경로. */
    @Test
    void 대시보드_카드가_보낸_OPEN과_month가_그대로_적용된다() throws Exception {
        given(mapper.findCases(anyList())).willReturn(List.of(OPEN_ROW));
        given(mapper.countByStatuses(anyList())).willReturn(1L);

        mockMvc.perform(get("/exceptions").param("status", "OPEN").param("month", "2026-05")
                        .with(user(SETTLE)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("statusFilter", "OPEN"))
                // month 는 예외 검색조건이 아니라 셸 기준월 — ShellAdvice 가 반영한다
                .andExpect(model().attribute("month", "2026-05"));

        verify(mapper).findCases(List.of(NEW, IN_REVIEW));
    }

    @Test
    void 실제_상태코드_필터는_그_값_하나로만_조회된다() throws Exception {
        given(mapper.findCases(List.of(RESOLVED))).willReturn(List.of(RESOLVED_ROW));
        given(mapper.countByStatuses(anyList())).willReturn(0L);

        mockMvc.perform(get("/exceptions").param("status", "RESOLVED").with(user(SETTLE)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("statusFilter", "RESOLVED"))
                .andExpect(content().string(containsString("필수값 누락")))
                .andExpect(content().string(containsString("href=\"/contracts/5\"")))
                .andExpect(content().string(containsString("<option value=\"RESOLVED\" selected=\"selected\">")));

        verify(mapper).findCases(List.of(RESOLVED));
    }

    @Test
    void 빈_상태값은_필터_없음_전체_조회다() throws Exception {
        given(mapper.findCases(List.of())).willReturn(List.of(OPEN_ROW, RESOLVED_ROW, UNKNOWN_SOURCE_ROW));
        given(mapper.countByStatuses(anyList())).willReturn(1L);

        mockMvc.perform(get("/exceptions").param("status", "").with(user(SETTLE)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("statusFilter", ""))
                .andExpect(content().string(containsString("<option value=\"\" selected=\"selected\">")))
                // 라우트가 없는 원천 유형은 링크 없이 텍스트로만 표시된다
                .andExpect(content().string(containsString("LEGACY_SOURCE:9")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("LEGACY_SOURCE:9</a>"))));

        verify(mapper).findCases(List.<ExceptionStatus>of());
    }

    @Test
    void 공백만_있는_상태값은_전체가_아니라_미지원으로_보고_기본_OPEN으로_되돌린다() throws Exception {
        // 명시적 빈 문자열("전체")과 달리 공백은 select 가 만들 수 없는 오타성 입력이다 —
        // 조용히 전체 조회로 새지 않게 한다.
        given(mapper.findCases(anyList())).willReturn(List.of());
        given(mapper.countByStatuses(anyList())).willReturn(0L);

        mockMvc.perform(get("/exceptions").param("status", " ").with(user(SETTLE)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("statusFilter", "OPEN"));

        verify(mapper).findCases(List.of(NEW, IN_REVIEW));
    }

    @Test
    void 미지원_상태값은_400이_아니라_기본_OPEN으로_되돌린다() throws Exception {
        // ShellAdvice 의 month 와 같은 정책 — select 가 보내는 값이라 조용히 복구한다
        given(mapper.findCases(anyList())).willReturn(List.of());
        given(mapper.countByStatuses(anyList())).willReturn(0L);

        mockMvc.perform(get("/exceptions").param("status", "NOPE").with(user(SETTLE)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("statusFilter", "OPEN"));

        verify(mapper).findCases(List.of(NEW, IN_REVIEW));
    }
}
