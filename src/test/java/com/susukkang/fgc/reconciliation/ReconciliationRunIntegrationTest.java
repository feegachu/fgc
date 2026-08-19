package com.susukkang.fgc.reconciliation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.reconciliation.dto.CreateReconciliationRunRequest;
import com.susukkang.fgc.reconciliation.port.ReconciliationExecutionRequestPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 설명 : PostgreSQL 기반 대사 실행 생성 API 통합 테스트
 *
 * @author yslee
 * @since 2026-08-12
 * @version 1.2
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReconciliationRunIntegrationTest {

    private static final LocalDate TEST_MONTH = LocalDate.of(2099, 1, 1);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ReconciliationExecutionRequestPort executionRequestPort;

    private final List<Long> reconciliationRunIds = new ArrayList<>();
    private final List<Long> validationRunIds = new ArrayList<>();
    private final List<Long> insurerIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        reconciliationRunIds.forEach(id -> jdbcTemplate.update(
                "DELETE FROM fgc.reconciliation_run WHERE reconciliation_run_id = ?", id));
        validationRunIds.forEach(id -> jdbcTemplate.update(
                "DELETE FROM fgc.validation_run WHERE validation_run_id = ?", id));
        insurerIds.forEach(id -> jdbcTemplate.update(
                "DELETE FROM fgc.insurer WHERE insurer_id = ?", id));
    }

    @Test
    void API가_대사실행을_RUNNING으로_생성하고_요청값을_보존한다() throws Exception {
        Long userId = settlementUserId();
        Long insurerId = insertInsurer();
        Long validationRunId = insertValidationRun(userId);

        mockMvc.perform(post("/api/v1/reconciliations")
                        .with(user(principal(userId)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(insurerId, validationRunId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("RUNNING"));

        Long reconciliationRunId = findReconciliationRunId(insurerId, validationRunId);
        reconciliationRunIds.add(reconciliationRunId);

        ReconciliationRunSnapshot snapshot = jdbcTemplate.queryForObject("""
                SELECT settlement_month, payment_stage, insurer_id, validation_run_id,
                       status, started_at IS NOT NULL, created_by
                  FROM fgc.reconciliation_run
                 WHERE reconciliation_run_id = ?
                """, (resultSet, rowNum) -> new ReconciliationRunSnapshot(
                resultSet.getObject("settlement_month", LocalDate.class),
                resultSet.getString("payment_stage"),
                resultSet.getLong("insurer_id"),
                resultSet.getLong("validation_run_id"),
                resultSet.getString("status"),
                resultSet.getBoolean(6),
                resultSet.getLong("created_by")
        ), reconciliationRunId);

        assertThat(snapshot).isEqualTo(new ReconciliationRunSnapshot(
                TEST_MONTH,
                "GA_TO_FC",
                insurerId,
                validationRunId,
                "RUNNING",
                true,
                userId
        ));
    }

    @Test
    void 동일_실행키를_두번_요청하면_두번째는_409이고_한건만_남는다() throws Exception {
        Long userId = settlementUserId();
        Long insurerId = insertInsurer();
        Long validationRunId = insertValidationRun(userId);
        String requestJson = objectMapper.writeValueAsString(request(insurerId, validationRunId));

        mockMvc.perform(post("/api/v1/reconciliations")
                        .with(user(principal(userId)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated());

        Long reconciliationRunId = findReconciliationRunId(insurerId, validationRunId);
        reconciliationRunIds.add(reconciliationRunId);

        mockMvc.perform(post("/api/v1/reconciliations")
                        .with(user(principal(userId)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FGC-RECO-002"))
                .andExpect(jsonPath("$.error.params.reconciliationRunId").value(reconciliationRunId));

        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM fgc.reconciliation_run
                 WHERE settlement_month = ?
                   AND payment_stage = 'GA_TO_FC'
                   AND insurer_id = ?
                   AND validation_run_id = ?
                """, Integer.class, TEST_MONTH, insurerId, validationRunId);
        assertThat(count).isEqualTo(1);
    }

    // 2026-08-12 yslee - 실행 요청 등록 실패 시 고아 RUNNING 방지 회귀 테스트
    // 기존 코드: 실행기 없이도 상태를 RUNNING으로 커밋
    // 문제: 실행 요청이 거절되면 실제 작업 없이 RUNNING 행이 영구 잔존할 수 있음
    // 개선: 실행 요청 예외가 생성 트랜잭션을 롤백해 reconciliation_run을 남기지 않는지 검증
    @Test
    void 실행요청이_거절되면_RUNNING_행을_남기지_않는다() throws Exception {
        Long userId = settlementUserId();
        Long insurerId = insertInsurer();
        Long validationRunId = insertValidationRun(userId);
        doThrow(new IllegalStateException("실행 요청 거절"))
                .when(executionRequestPort).requestExecution(any());

        mockMvc.perform(post("/api/v1/reconciliations")
                        .with(user(principal(userId)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(insurerId, validationRunId))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-500"));

        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM fgc.reconciliation_run
                 WHERE settlement_month = ?
                   AND payment_stage = 'GA_TO_FC'
                   AND insurer_id = ?
                   AND validation_run_id = ?
                """, Integer.class, TEST_MONTH, insurerId, validationRunId);
        assertThat(count).isZero();
    }

    private Long insertInsurer() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.insurer (insurer_code, insurer_name, insurer_type)
                VALUES (?, ?, 'LIFE')
                RETURNING insurer_id
                """, Long.class, "RECO-" + suffix, "대사 통합 테스트 보험사 " + suffix);
        insurerIds.add(id);
        return id;
    }

    private Long insertValidationRun(Long userId) {
        int runNo = Math.abs(UUID.randomUUID().hashCode() % 1_000_000) + 100_000;
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (
                    validation_month, run_no, run_type, triggered_by
                ) VALUES (?, ?, 'PRE_CONFIRM', ?)
                RETURNING validation_run_id
                """, Long.class, TEST_MONTH, runNo, userId);
        validationRunIds.add(id);
        return id;
    }

    private Long findReconciliationRunId(Long insurerId, Long validationRunId) {
        return jdbcTemplate.queryForObject("""
                SELECT reconciliation_run_id
                  FROM fgc.reconciliation_run
                 WHERE settlement_month = ?
                   AND payment_stage = 'GA_TO_FC'
                   AND insurer_id = ?
                   AND validation_run_id = ?
                """, Long.class, TEST_MONTH, insurerId, validationRunId);
    }

    private Long settlementUserId() {
        return jdbcTemplate.queryForObject(
                "SELECT user_id FROM fgc.app_user WHERE login_id = 'settle01'",
                Long.class
        );
    }

    private static CreateReconciliationRunRequest request(Long insurerId, Long validationRunId) {
        return new CreateReconciliationRunRequest(
                "2099-01",
                "GA_TO_FC",
                insurerId,
                validationRunId
        );
    }

    private static FgcUserDetails principal(Long userId) {
        AppUserView view = new AppUserView();
        view.setUserId(userId);
        view.setLoginId("settle01");
        view.setPasswordHash("{noop}x");
        view.setUserName("정산담당자");
        view.setRoleCode("SETTLEMENT");
        view.setAccountStatus("ACTIVE");
        return new FgcUserDetails(view, true, true);
    }

    private record ReconciliationRunSnapshot(
            LocalDate settlementMonth,
            String paymentStage,
            Long insurerId,
            Long validationRunId,
            String status,
            boolean started,
            Long createdBy
    ) {
    }
}
