package com.susukkang.fgc.schedule.view;

import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.web.ScreenViewController;
import com.susukkang.fgc.common.web.ShellAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ScreenViewController.class)
@Import({
        ShellAdvice.class,
        SecurityConfig.class,
        MessageSourceAutoConfiguration.class,
        FgcMessageResolver.class,
        ConstraintErrorCodeResolver.class
})
@TestPropertySource(properties = "fgc.demo-month=2026-07")
class ScheduleListViewTest {

    @Autowired
    private MockMvc mockMvc;

    // FGC-FUN-036·039·040 / REG-01·19
    @Test
    void rendersScheduleListWithApiFiltersAndFeatureAssets() throws Exception {
        mockMvc.perform(get("/schedules").with(user(settlementUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("FGC-UI-SCHE-W01")))
                .andExpect(content().string(containsString("data-schedule-filter-form")))
                .andExpect(content().string(containsString("name=\"contractNo\"")))
                .andExpect(content().string(containsString("name=\"stage\"")))
                .andExpect(content().string(containsString("name=\"regime\"")))
                .andExpect(content().string(containsString("name=\"purpose\"")))
                .andExpect(content().string(containsString("name=\"status\"")))
                .andExpect(content().string(containsString("value=\"OPERATIONAL\" selected")))
                .andExpect(content().string(containsString("/css/features/schedule.css")))
                .andExpect(content().string(containsString("/js/features/schedule/schedule-list.js")))
                .andExpect(content().string(not(containsString("cdn.tailwindcss.com"))))
                .andExpect(content().string(not(containsString("FGC.SEED"))));
    }

    private static FgcUserDetails settlementUser() {
        AppUserView user = new AppUserView();
        user.setUserId(1L);
        user.setLoginId("settle01");
        user.setPasswordHash("x");
        user.setUserName("정산담당");
        user.setRoleCode("SETTLEMENT");
        return new FgcUserDetails(user, true, true);
    }
}
