package com.susukkang.fgc.validation.repository;

import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** requestId가 식별 파라미터였던 기존 실행도 원래 JobInstance로 재시작한다. */
@Repository
@RequiredArgsConstructor
public class MonthlyJobExecutionLookup {
    private final EntityManager entityManager;
    private final JobExplorer jobExplorer;

    @Transactional(readOnly = true)
    public JobExecution findFailedExecution(ValidationRunRow run) {
        var ids = entityManager.createNativeQuery("""
                SELECT DISTINCT ji.job_instance_id
                  FROM batch_job_instance ji JOIN batch_job_execution je USING(job_instance_id)
                 WHERE ji.job_name='MonthlyValidationJob'
                   AND EXISTS (SELECT 1 FROM batch_job_execution_params p WHERE p.job_execution_id=je.job_execution_id
                               AND p.parameter_name='validationMonth' AND p.parameter_value=:month)
                   AND EXISTS (SELECT 1 FROM batch_job_execution_params p WHERE p.job_execution_id=je.job_execution_id
                               AND p.parameter_name='runNo' AND p.parameter_value=:runNo)
                   AND EXISTS (SELECT 1 FROM batch_job_execution_params p WHERE p.job_execution_id=je.job_execution_id
                               AND p.parameter_name='runType' AND p.parameter_value=:runType)
                   AND EXISTS (SELECT 1 FROM batch_job_execution_params p WHERE p.job_execution_id=je.job_execution_id
                               AND p.parameter_name='triggeredBy' AND p.parameter_value=:triggeredBy)
                """, Long.class)
                .setParameter("month", DateUtil.formatSettlementMonth(run.getValidationMonth()))
                .setParameter("runNo", run.getRunNo().toString())
                .setParameter("runType", run.getRunType())
                .setParameter("triggeredBy", run.getTriggeredBy().toString()).getResultList();
        if (ids.size() != 1) {
            throw new IllegalStateException("재시작할 월 배치 인스턴스가 없거나 여러 개입니다.");
        }
        var instance = jobExplorer.getJobInstance((Long) ids.getFirst());
        JobExecution execution = instance == null ? null : jobExplorer.getLastJobExecution(instance);
        if (execution == null || execution.getStatus() != org.springframework.batch.core.BatchStatus.FAILED) {
            throw new IllegalStateException("마지막 월 배치 실행이 FAILED가 아닙니다.");
        }
        return execution;
    }
}
