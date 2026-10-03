package com.susukkang.fgc.validation.repository;

import com.susukkang.fgc.validation.entity.ContractStatusEventProcessing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 설명 : fgc.contract_status_event / fgc.contract_status_event_processing 전용
 * Repository(#380). 사건 원본은 이미 JPA로 전환된 {@code ContractStatusEvent}
 * 엔티티(contract 패키지)를 그대로 재사용한다 — 중복 엔티티를 두지 않는다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-10-03
 */
public interface ContractStatusEventProcessingRepository
        extends JpaRepository<ContractStatusEventProcessing, Long> {

    /**
     * contractId의 상태 이벤트 중, jobName 기준으로 아직 SUCCEEDED 처리이력이 없는 것만
     * effective_at ASC, event_seq ASC 순서로 돌려준다.
     */
    @Query("""
            SELECT e.contractStatusEventId
              FROM ContractStatusEvent e
             WHERE e.contractId = :contractId
               AND NOT EXISTS (
                   SELECT 1 FROM ContractStatusEventProcessing p
                    WHERE p.contractStatusEventId = e.contractStatusEventId
                      AND p.processingJob = :jobName
                      AND p.processingStatus = 'SUCCEEDED'
               )
             ORDER BY e.effectiveAt ASC, e.eventSeq ASC
            """)
    List<Long> findPendingEventIds(@Param("contractId") Long contractId, @Param("jobName") String jobName);

    /**
     * 이 Job이 이벤트 하나를 처리한 결과(성공/실패)를 남긴다.
     * 배치 공통규칙 1 "모든 Writer는 UPSERT 또는 ON CONFLICT DO NOTHING" 준수 — SUCCEEDED는
     * uq_cse_processing_succeeded 부분 유니크 인덱스와 충돌할 수 있는 유일한 케이스라
     * ON CONFLICT DO NOTHING이 있으면 재처리해도 조용히 무시된다. FAILED는 애초에 유니크
     * 제약이 없어 이 절이 걸릴 일이 없다(항상 새 행으로 쌓인다). 엔티티 save()로는
     * ON CONFLICT DO NOTHING을 표현할 수 없어 네이티브 쿼리로 유지한다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO fgc.contract_status_event_processing
                (contract_status_event_id, processing_job, processing_status, validation_run_id, failure_reason)
            VALUES
                (:contractStatusEventId, :jobName, :processingStatus, :validationRunId, :failureReason)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    void insertProcessing(@Param("contractStatusEventId") Long contractStatusEventId,
                           @Param("jobName") String jobName,
                           @Param("processingStatus") String processingStatus,
                           @Param("validationRunId") Long validationRunId,
                           @Param("failureReason") String failureReason);
}
