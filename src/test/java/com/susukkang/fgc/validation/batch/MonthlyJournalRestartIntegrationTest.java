package com.susukkang.fgc.validation.batch;

import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.repository.ValidationRunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.SimpleJob;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전용 Testcontainers DB에서 실제 월 배치 기동·원장·진행 리스너를 검증한다.
 * 계산 Step의 정확성은 각 도메인 테스트 범위다. 여기서는 대상과 확정 원천을 준비하고
 * 실제 생성/기표/균형검사/예외 Step을 연결하여 25번째 DB INSERT 실패를 주입한다.
 * 체크포인트만 모의한 표가 아니라 journal_header/line의 실제 멱등성과 부분 커밋을 확인한다.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:tc:postgresql:17:///fgc_restart_672?currentSchema=fgc")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MonthlyJournalRestartIntegrationTest {
    private static final LocalDate MONTH = LocalDate.of(2092, 6, 1);
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Job monthlyValidationJob;
    @Autowired private JobLauncher jobLauncher;
    @Autowired private MonthlyValidationJobTrigger trigger;
    @Autowired private ValidationRunRepository runs;
    @Autowired private Step createRunStep;
    @Autowired private Step journalPostingStep;
    @Autowired private Step imbalanceCheckStep;
    @Autowired private Step exceptionGenerationStep;

    @Test
    void twentyFifthJournalFailureRestartsSameInstanceAndPostsThirtySourcesOnce() throws Exception {
        // 공유 개발/운영 DB를 사용하지 않는다. 이 테스트 전용 컨텍스트만 Job 배선을 바꾼다.
        ((SimpleJob) monthlyValidationJob).setSteps(List.of(
                createRunStep, journalPostingStep, imbalanceCheckStep, exceptionGenerationStep));
        Long userId = jdbc.queryForObject("SELECT MIN(user_id) FROM fgc.app_user", Long.class);
        Long runId = jdbc.queryForObject("""
                INSERT INTO fgc.validation_run(validation_month,run_no,run_type,status,triggered_by)
                VALUES (?,1,'MONTHLY','CREATED',?) RETURNING validation_run_id
                """, Long.class, MONTH, userId);
        var contract = jdbc.queryForMap("""
                SELECT contract_id,product_offering_id,agent_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1
                """);
        Long contractId = ((Number) contract.get("contract_id")).longValue();
        Long agentId = ((Number) contract.get("agent_id")).longValue();
        jdbc.update("""
                INSERT INTO fgc.validation_target(validation_run_id,contract_id,product_offering_id,selection_status,selection_reason)
                VALUES (?,?,?,'SELECTED','재시작 전용 검증 대상')
                """, runId, contractId, contract.get("product_offering_id"));
        Long policyId = jdbc.queryForObject("SELECT MIN(policy_version_id) FROM fgc.policy_version WHERE status='ACTIVE'", Long.class);
        Long itemId = jdbc.queryForObject("SELECT commission_item_id FROM fgc.commission_item WHERE item_code='BASE_COMMISSION'", Long.class);
        for (int index = 1; index <= 30; index++) {
            Long paymentId = jdbc.queryForObject("""
                    INSERT INTO fgc.commission_transaction(payment_stage,source_type,source_business_key,
                        source_contract_id,recipient_agent_id,commission_item_id,policy_version_id,
                        settlement_month,due_date,amount,cashflow_type)
                    VALUES ('GA_TO_FC','GA_MANUAL_PAYMENT',?,?,?,?,?,?,?,40000,'PAYMENT')
                    RETURNING commission_transaction_id
                    """, Long.class, "IT-672-" + index, contractId, agentId, itemId, policyId, MONTH, MONTH);
            jdbc.update("""
                    INSERT INTO fgc.transaction_attribution(commission_transaction_id,attribution_seq,attribution_scope,
                        contract_id,attribution_date,attribution_month,attributed_amount,inclusion_status_snapshot,attribution_method)
                    VALUES (?,1,'CONTRACT',?,?,?,40000,'INCLUDED','DIRECT')
                    """, paymentId, contractId, MONTH, MONTH);
            jdbc.update("UPDATE fgc.commission_transaction SET status='CONFIRMED' WHERE commission_transaction_id=?", paymentId);
        }
        var watermarks = jdbc.queryForList("SELECT * FROM fgc.batch_watermark ORDER BY job_name,step_name");
        jdbc.execute("CREATE SEQUENCE fgc.it_672_journal_attempt");
        jdbc.execute("""
                CREATE FUNCTION fgc.it_672_fail_twenty_fifth() RETURNS trigger LANGUAGE plpgsql AS $function$
                BEGIN
                    IF NEW.validation_run_id = %d AND nextval('fgc.it_672_journal_attempt') = 25 THEN
                        RAISE EXCEPTION 'IT-672 intended failure on source 25';
                    END IF;
                    RETURN NEW;
                END
                $function$
                """.formatted(runId));
        jdbc.execute("""
                CREATE TRIGGER it_672_fail_twenty_fifth BEFORE INSERT ON fgc.journal_header
                FOR EACH ROW EXECUTE FUNCTION fgc.it_672_fail_twenty_fifth()
                """);
        // 과거 버전과 똑같이 requestId를 식별값으로 넣어도 재시작 시 바꾸지 않아야 한다.
        var parameters = new JobParametersBuilder().addString("validationMonth", "2092-06")
                .addLong("runNo", 1L).addString("runType", "MONTHLY").addLong("triggeredBy", userId)
                .addString("requestId", "IT-672-initial", true).toJobParameters();
        var first = jobLauncher.run(monthlyValidationJob, parameters);
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(runs.findById(runId).orElseThrow().getCurrentStep()).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT last_value FROM fgc.it_672_journal_attempt", Long.class)).isEqualTo(25L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fgc.journal_header WHERE validation_run_id=?", Long.class, runId))
                .isEqualTo(24L);
        var failedRow = ValidationRunRow.from(runs.findById(runId).orElseThrow());
        var restarted = trigger.launch(failedRow, "IT-672-restart").get(60, TimeUnit.SECONDS);
        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(restarted.getJobInstance().getInstanceId()).isEqualTo(first.getJobInstance().getInstanceId());
        assertThat(restarted.getStepExecutions()).extracting(step -> step.getStepName())
                .doesNotContain("createRunStep").contains("journalPostingStep", "imbalanceCheckStep", "exceptionGenerationStep");
        assertThat(runs.findById(runId).orElseThrow().getStatus().name()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fgc.journal_header WHERE validation_run_id=? AND status='POSTED'", Long.class, runId))
                .isEqualTo(30L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT journal_type,source_entity_type,source_entity_id,revision_no
                      FROM fgc.journal_header WHERE validation_run_id=?
                     GROUP BY journal_type,source_entity_type,source_entity_id,revision_no HAVING COUNT(*) <> 1
                ) duplicates
                """, Long.class, runId)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM fgc.journal_line l JOIN fgc.journal_header h USING(journal_header_id)
                 WHERE h.validation_run_id=?
                """, Long.class, runId)).isEqualTo(60L);
        assertThat(runs.findLatestAttemptRequestId(runId)).isEqualTo("IT-672-restart");
        assertThat(jdbc.queryForList("SELECT * FROM fgc.batch_watermark ORDER BY job_name,step_name"))
                .isEqualTo(watermarks);
    }
}
