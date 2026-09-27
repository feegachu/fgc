package com.susukkang.fgc.contract.entity;

import com.susukkang.fgc.common.code.SurrenderValueType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 설명 : 계약별 기준일 재무 스냅샷 엔티티
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-27
 */
@Entity
@Table(
        name = "contract_financial_snapshot",
        schema = "fgc",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_contract_financial_snapshot",
                        columnNames = {
                                "contract_id",
                                "as_of_date",
                                "surrender_value_type"
                        }
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContractFinancialSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "contract_financial_snapshot_id", nullable = false)
    private Long contractFinancialSnapshotId;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    // 스냅샷 기준일
    @Column(name = "as_of_date", nullable = false)
    private LocalDate asOfDate;

    // 계약 차월
    @Column(name = "contract_month_no", nullable = false)
    private Integer contractMonthNo;

    // 기준일까지 누적 납입보험료
    @Column(
            name = "cumulative_paid_premium",
            nullable = false,
            precision = 15,
            scale = 2
    )
    private BigDecimal cumulativePaidPremium;

    // 해약환급금 — 확인되지 않은 경우 NULL
    @Column(name = "surrender_value", precision = 15, scale = 2)
    private BigDecimal surrenderValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "surrender_value_type", nullable = false, length = 20)
    private SurrenderValueType surrenderValueType;

    @Column(name = "refund_rate_table_id")
    private Long refundRateTableId;

    @Column(name = "source_ref", length = 500)
    private String sourceRef;

    // DB 기본값으로 생성
    @Column(
            name = "created_at",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private OffsetDateTime createdAt;
}