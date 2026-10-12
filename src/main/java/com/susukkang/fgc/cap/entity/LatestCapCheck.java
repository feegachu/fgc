package com.susukkang.fgc.cap.entity;

import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** 최신 판정 선택 규칙(DISTINCT ON, checked_at/id 역순)은 기존 PostgreSQL 뷰가 소유한다. */
@Entity
@Immutable
@Table(name = "vw_latest_cap_check", schema = "fgc")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LatestCapCheck {
    @Id
    @Column(name = "cap_check_id")
    private Long capCheckId;
    @Column(name = "contract_id")
    private Long contractId;
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_stage")
    private PaymentStage paymentStage;
    @Enumerated(EnumType.STRING)
    @Column(name = "result_status")
    private CapResultStatus resultStatus;
}
