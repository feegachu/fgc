package com.susukkang.fgc.contract.entity;

import com.susukkang.fgc.common.code.ContractStatus;
import com.susukkang.fgc.common.code.DataOrigin;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 설명 : 계약 상태사건 엔티티
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-27
 */
@Entity
@Table(
        name = "contract_status_event",
        schema = "fgc",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_contract_status_event_seq",
                        columnNames = {"contract_id", "event_seq"}
                ),
                @UniqueConstraint(
                        name = "uq_contract_status_event_source",
                        columnNames = {"source_system", "source_event_key"}
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContractStatusEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "contract_status_event_id", nullable = false)
    private Long contractStatusEventId;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    // 계약 내 사건 순번
    @Column(name = "event_seq", nullable = false)
    private Integer eventSeq;

    // 최초 등록 사건은 이전 상태가 없으므로 NULL 허용
    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 20)
    private ContractStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 20)
    private ContractStatus newStatus;

    // 상태 변경의 효력 발생 시각
    @Column(name = "effective_at", nullable = false)
    private OffsetDateTime effectiveAt;

    // 사건 수신 시각
    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "reason_code", length = 40)
    private String reasonCode;

    @Column(name = "source_system", nullable = false, length = 60)
    private String sourceSystem;

    @Column(name = "source_event_key", nullable = false, length = 160)
    private String sourceEventKey;

    @Column(name = "source_version", length = 60)
    private String sourceVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_origin", nullable = false, length = 20)
    private DataOrigin dataOrigin;

    @Column(name = "source_ref", length = 500)
    private String sourceRef;

    @Column(name = "registered_by")
    private Long registeredBy;

    // DB 기본값으로 생성
    @Column(
            name = "created_at",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private OffsetDateTime createdAt;

    // ID와 생성 시각은 DB가 관리하므로 생성용 Builder에서 제외한다.
    @Builder
    private ContractStatusEvent(
            Long contractId,
            Integer eventSeq,
            ContractStatus previousStatus,
            ContractStatus newStatus,
            OffsetDateTime effectiveAt,
            OffsetDateTime receivedAt,
            String reasonCode,
            String sourceSystem,
            String sourceEventKey,
            String sourceVersion,
            DataOrigin dataOrigin,
            String sourceRef,
            Long registeredBy
    ) {
        this.contractId = contractId;
        this.eventSeq = eventSeq;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.effectiveAt = effectiveAt;
        this.receivedAt = receivedAt;
        this.reasonCode = reasonCode;
        this.sourceSystem = sourceSystem;
        this.sourceEventKey = sourceEventKey;
        this.sourceVersion = sourceVersion;
        this.dataOrigin = dataOrigin;
        this.sourceRef = sourceRef;
        this.registeredBy = registeredBy;
    }
}
