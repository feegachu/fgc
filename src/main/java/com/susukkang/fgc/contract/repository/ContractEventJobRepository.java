package com.susukkang.fgc.contract.repository;

import com.susukkang.fgc.contract.dto.ContractStatusEffect;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 설명 : 상태 사건 후속 작업(contract_event_job)과 영향 기록(contract_status_event_effect). 모든 값은 바인딩한다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-10-30
 */
@Repository
@RequiredArgsConstructor
public class ContractEventJobRepository {

    private static final String UNFINISHED_JOB = """
            SELECT 1 FROM fgc.contract_event_job
             WHERE contract_status_event_id = ? AND processing_job = ? AND state <> 'SUCCEEDED'
            """;

    private final JdbcTemplate jdbc;

    public record JobKey(Long eventId, String job) {
    }

    public void insertPending(Long eventId, String job) {
        jdbc.update("""
                INSERT INTO fgc.contract_event_job (contract_status_event_id, processing_job) VALUES (?, ?)
                """, eventId, job);
    }

    /**
     * 지금 처리할 작업 하나를 잡으면서 시도 횟수와 다음 재시도 시각(1·2·4·8분 뒤)을 먼저 기록한다.
     * 호출자가 바로 커밋하므로 처리가 실패하거나 중단돼도 그 시각 전에는 다른 worker가 같은 작업을 잡지 않는다.
     * 다른 worker가 잡은 작업은 건너뛰고, 같은 계약의 앞선 사건에 끝나지 않은 작업이 있으면 사건 순번 순서를 지키기 위해 기다린다.
     */
    public Optional<JobKey> claimNext(int maxAttempts) {
        return jdbc.query("""
                WITH next_job AS (
                    SELECT j.contract_status_event_id, j.processing_job
                      FROM fgc.contract_event_job j
                      JOIN fgc.contract_status_event e ON e.contract_status_event_id = j.contract_status_event_id
                     WHERE j.state <> 'SUCCEEDED'
                       AND j.attempt_count < ?
                       AND j.next_retry_at <= clock_timestamp()
                       AND NOT EXISTS (
                           SELECT 1
                             FROM fgc.contract_event_job prior
                             JOIN fgc.contract_status_event pe ON pe.contract_status_event_id = prior.contract_status_event_id
                            WHERE pe.contract_id = e.contract_id
                              AND pe.event_seq < e.event_seq
                              AND prior.state <> 'SUCCEEDED')
                     ORDER BY j.next_retry_at, e.contract_id, e.event_seq, j.processing_job
                     LIMIT 1
                     FOR UPDATE OF j SKIP LOCKED)
                UPDATE fgc.contract_event_job claimed
                   SET attempt_count = claimed.attempt_count + 1,
                       last_attempt_at = clock_timestamp(),
                       next_retry_at = clock_timestamp() + make_interval(mins => power(2, claimed.attempt_count)::int)
                  FROM next_job
                 WHERE claimed.contract_status_event_id = next_job.contract_status_event_id
                   AND claimed.processing_job = next_job.processing_job
                RETURNING claimed.contract_status_event_id, claimed.processing_job
                """, (rs, row) -> new JobKey(rs.getLong(1), rs.getString(2)), maxAttempts)
                .stream().findFirst();
    }

    /** 처리 직전에 작업 행을 다시 잠근다. 다른 worker가 잡고 있거나 이미 성공했으면 false다. */
    public boolean lockForProcessing(JobKey job) {
        return found(UNFINISHED_JOB + "FOR UPDATE SKIP LOCKED", job);
    }

    /** 실패를 기록하기 전에 작업 행을 다시 잠근다. 다른 worker가 처리 중이면 끝날 때까지 기다리고, 성공했으면 false다. */
    public boolean lockForFailure(JobKey job) {
        return found(UNFINISHED_JOB + "FOR UPDATE", job);
    }

    private boolean found(String sql, JobKey job) {
        return !jdbc.queryForList(sql, Integer.class, job.eventId(), job.job()).isEmpty();
    }

    public void insertEffects(JobKey job, List<ContractStatusEffect> effects) {
        jdbc.batchUpdate("""
                INSERT INTO fgc.contract_status_event_effect
                    (contract_status_event_id, processing_job, entity_type, entity_id, previous_state, next_state,
                     amount, policy_version_id, reason_code, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, effects, effects.size(), (ps, effect) -> {
            ps.setLong(1, job.eventId());
            ps.setString(2, job.job());
            ps.setString(3, effect.entityType().name());
            ps.setLong(4, effect.entityId());
            ps.setString(5, effect.previousState());
            ps.setString(6, effect.nextState());
            ps.setBigDecimal(7, effect.amount());
            ps.setObject(8, effect.policyVersionId());
            ps.setString(9, effect.reasonCode());
            ps.setString(10, effect.reason());
        });
    }

    // 시도 횟수·재시도 시각은 claimNext가 이미 기록했다.
    public void markSucceeded(JobKey job) {
        jdbc.update("""
                UPDATE fgc.contract_event_job SET state = 'SUCCEEDED', error_code = NULL
                 WHERE contract_status_event_id = ? AND processing_job = ?
                """, job.eventId(), job.job());
    }

    public void markFailed(JobKey job, String errorCode) {
        jdbc.update("""
                UPDATE fgc.contract_event_job SET state = 'FAILED', error_code = ?
                 WHERE contract_status_event_id = ? AND processing_job = ?
                """, errorCode, job.eventId(), job.job());
    }
}
