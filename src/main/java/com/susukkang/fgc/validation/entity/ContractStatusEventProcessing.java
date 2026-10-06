package com.susukkang.fgc.validation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 설명 : fgc.contract_status_event_processing 1행 — 계약상태 사건을 어느 배치가 언제
 * 처리했는지의 기록(#380). 사건 원본(contract_status_event, append-only)과 분리된다.
 *
 * PK는 대리키다(V6 — 자연키를 PK로 두면 실패 이력을 못 쌓긴 때문). 대신 부분 UNIQUE
 * 인덱스 {@code uq_cse_processing_succeeded}가 "사건×Job당 SUCCEEDED는 최대 1행"을
 * 강제한다 — FAILED는 유니크 제약이 없어 매번 새 행으로 쌓인다(증거 보존).
 * 이 테이블도 append-only 트리거가 있어 애플리케이션에서 save()로 쓰기만 하고
 * UPDATE/DELETE는 하지 않는다.
 */
@Entity
@Table(name = "contract_status_event_processing", schema = "fgc")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContractStatusEventProcessing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "contract_status_event_processing_id", nullable = false)
    private Long contractStatusEventProcessingId;

    @Column(name = "contract_status_event_id", nullable = false)
    private Long contractStatusEventId;

    @Column(name = "processing_job", nullable = false, length = 100)
    private String processingJob;

    @Column(name = "processing_status", nullable = false, length = 20)
    private String processingStatus;

    @Column(name = "validation_run_id")
    private Long validationRunId;

    @Column(name = "failure_reason", length = 2000)
    private String failureReason;

    @Column(name = "processed_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime processedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
