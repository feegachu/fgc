package com.susukkang.fgc.contract.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.contract.dto.ContractUpdateResponse.FollowUp;
import com.susukkang.fgc.contract.entity.ContractStatusEvent;
import com.susukkang.fgc.contract.repository.ContractEventJobRepository;
import com.susukkang.fgc.contract.repository.ContractEventJobRepository.JobKey;
import com.susukkang.fgc.contract.repository.ContractLockRepository;
import com.susukkang.fgc.contract.repository.ContractStatusEventRepository;
import com.susukkang.fgc.validation.repository.ContractStatusEventProcessingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 설명 : FUN-026 상태 사건 후속 작업의 등록과 처리.
 * 작업은 사건과 같은 트랜잭션에 DB로 남으므로 커밋 직후 프로세스가 멈춰도 다음 실행이 이어서 처리한다.
 * 성공·실패는 기존 contract_status_event_processing에 덧붙이고, 실패한 사건은 저장된 계약·사건을 되돌리지 않는다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-10-30
 */
@Slf4j
@Service
public class ContractStatusFollowUpService {

    // shortcut: 5번 시도(처리 중 중단 포함)하면 자동 재시도를 멈추고 같은 계약의 뒤 사건도 기다린다. 수동 재시도 화면이 생기면 그 경로로 푼다.
    static final int MAX_ATTEMPTS = 5;
    private static final int MAX_JOBS_PER_RUN = 100;
    private static final int FAILURE_REASON_MAX_LENGTH = 2000;

    private final ObjectProvider<ContractStatusFollowUpProcessor> processors;
    private final ContractEventJobRepository jobs;
    private final ContractStatusEventRepository events;
    private final ContractStatusEventProcessingRepository processingHistory;
    private final ContractLockRepository contractLocks;
    private final TransactionTemplate transaction;

    // 테스트는 끄고 runDueJobs()를 직접 부른다.
    @Value("${fgc.contract.status-follow-up.enabled:true}")
    private boolean enabled;

    public ContractStatusFollowUpService(
            ObjectProvider<ContractStatusFollowUpProcessor> processors,
            ContractEventJobRepository jobs,
            ContractStatusEventRepository events,
            ContractStatusEventProcessingRepository processingHistory,
            ContractLockRepository contractLocks,
            PlatformTransactionManager transactionManager
    ) {
        this.processors = processors;
        this.jobs = jobs;
        this.events = events;
        this.processingHistory = processingHistory;
        this.contractLocks = contractLocks;
        // 호출자에 트랜잭션이 있어도 작업 하나를 독립 트랜잭션으로 커밋·롤백한다.
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 사건을 저장한 트랜잭션 안에서 이 사건이 필요한 처리기마다 대기 작업을 만든다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public FollowUp enqueue(ContractStatusEvent event) {
        Long eventId = event.getContractStatusEventId();
        List<String> jobNames = processors.orderedStream()
                .filter(processor -> processor.supports(event))
                .map(ContractStatusFollowUpProcessor::jobName)
                .toList();
        jobNames.forEach(job -> jobs.insertPending(eventId, job));
        return new FollowUp(jobNames.isEmpty() ? FollowUp.Status.NOT_REQUIRED : FollowUp.Status.PENDING, eventId);
    }

    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.SECONDS)
    public void runScheduled() {
        if (enabled) {
            runDueJobs();
        }
    }

    /** 지금 처리할 수 있는 작업을 하나씩 처리하고 처리한(성공·실패) 수를 돌려준다. */
    public int runDueJobs() {
        int handled = 0;
        while (handled < MAX_JOBS_PER_RUN && runOne()) {
            handled++;
        }
        return handled;
    }

    private boolean runOne() {
        // 1. 잡으면서 시도 횟수·다음 재시도 시각을 먼저 커밋한다. 실패·중단 뒤에도 그 시각 전에는 다른 worker가 잡지 않는다.
        JobKey job = transaction.execute(status -> jobs.claimNext(MAX_ATTEMPTS).orElse(null));
        if (job == null) {
            return false;
        }
        // savepoint로 처리기만 되돌리지 않는 이유: JpaTransactionManager의 중첩 트랜잭션은 JPA 영속성 컨텍스트를 되돌리지 않는다.
        try {
            transaction.executeWithoutResult(status -> process(job));
        } catch (RuntimeException failure) {
            log.warn("상태 사건 후속 작업 실패: eventId={}, job={}", job.eventId(), job.job(), failure);
            // 2. 작업 행을 다시 잠가 아직 끝나지 않았을 때만 실패를 남긴다(다른 worker의 성공 뒤에 실패 이력이 붙지 않게).
            transaction.executeWithoutResult(status -> {
                if (jobs.lockForFailure(job)) {
                    processingHistory.insertProcessing(job.eventId(), job.job(), "FAILED", null, failureReason(failure));
                    jobs.markFailed(job, errorCode(failure));
                }
            });
        }
        return true;
    }

    /** 작업·계약 행을 잠그고 처리기 결과·영향·성공 이력·작업 완료를 한 트랜잭션에 남긴다. */
    private void process(JobKey job) {
        if (!jobs.lockForProcessing(job)) {
            return;
        }
        ContractStatusEvent event = events.findById(job.eventId()).orElseThrow();
        contractLocks.lock(event.getContractId());
        ContractStatusFollowUpProcessor processor = processors.orderedStream()
                .filter(candidate -> candidate.jobName().equals(job.job()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("후속 처리기를 찾을 수 없습니다: " + job.job()));
        jobs.insertEffects(job, processor.process(event));
        processingHistory.insertProcessing(job.eventId(), job.job(), "SUCCEEDED", null, null);
        jobs.markSucceeded(job);
    }

    private static String errorCode(RuntimeException failure) {
        return failure instanceof FgcBusinessException business
                ? business.getErrorCode().getCode()
                : failure.getClass().getSimpleName();
    }

    private static String failureReason(RuntimeException failure) {
        String reason = errorCode(failure) + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
        return reason.length() <= FAILURE_REASON_MAX_LENGTH ? reason : reason.substring(0, FAILURE_REASON_MAX_LENGTH);
    }
}
