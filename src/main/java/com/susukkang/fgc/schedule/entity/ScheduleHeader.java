package com.susukkang.fgc.schedule.entity;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.ScheduleHeaderStatus;
import com.susukkang.fgc.schedule.code.SchedulePurpose;
import com.susukkang.fgc.schedule.code.ScheduleRegime;
import com.susukkang.fgc.schedule.dto.ScheduleHeaderInsertDTO;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 설명 : 계약·지급단계별 예상 스케줄 버전 헤더 엔티티.
 * calculation_input·generated_by는 기존 저장 경로가 쓰지 않으므로 매핑하지 않고 DB 기본값을 유지한다.
 * 상태·활성 여부 전이는 조건부 벌크 갱신(ScheduleWriteRepository)으로만 처리하며 더티 체킹에 의존하지 않는다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-10-05
 */
@Entity
@Table(name = "schedule_header", schema = "fgc")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScheduleHeader {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "schedule_header_id")
    private Long scheduleHeaderId;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_stage", nullable = false, length = 20)
    private PaymentStage paymentStage;

    @Column(name = "policy_version_id", nullable = false)
    private Long policyVersionId;

    @Column(name = "schedule_version_no", nullable = false)
    private Integer scheduleVersionNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_purpose", nullable = false, length = 20)
    private SchedulePurpose schedulePurpose;

    @Column(name = "scenario_code", length = 60)
    private String scenarioCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_regime", nullable = false, length = 30)
    private ScheduleRegime scheduleRegime;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ScheduleHeaderStatus status;

    @Column(name = "active_yn", nullable = false)
    private Boolean activeYn;

    @Column(name = "generation_reason", nullable = false, length = 40)
    private String generationReason;

    @Column(name = "regenerated_from_id")
    private Long regeneratedFromId;

    // 월 검증 배치가 생성한 헤더에만 연결 후 채운다(V21).
    @Column(name = "validation_run_id")
    private Long validationRunId;

    @Column(name = "generated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime generatedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** 기존 INSERT가 넣던 컬럼만 옮긴다. ID·생성 시각은 DB가 채운다. */
    public static ScheduleHeader from(ScheduleHeaderInsertDTO dto) {
        ScheduleHeader header = new ScheduleHeader();
        header.contractId = dto.getContractId();
        header.paymentStage = dto.getPaymentStage();
        header.policyVersionId = dto.getPolicyVersionId();
        header.scheduleVersionNo = dto.getScheduleVersionNo();
        header.schedulePurpose = dto.getSchedulePurpose();
        header.scenarioCode = dto.getScenarioCode();
        header.scheduleRegime = dto.getScheduleRegime();
        header.status = dto.getStatus();
        header.activeYn = dto.getActiveYn();
        header.generationReason = dto.getGenerationReason();
        header.regeneratedFromId = dto.getRegeneratedFromId();
        return header;
    }
}
