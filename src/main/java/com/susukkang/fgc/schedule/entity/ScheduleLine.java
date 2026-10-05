package com.susukkang.fgc.schedule.entity;

import com.susukkang.fgc.common.code.CalculationType;
import com.susukkang.fgc.common.code.ScheduleLineStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 설명 : 회차별 예상 수입·지급액 엔티티. 대사의 기대값 원천이다.
 * 저장은 문장 단위 확정 가드 트리거와 기존 처리량을 유지하기 위해 다건 VALUES 네이티브 INSERT로 한다
 * (IDENTITY 키는 Hibernate JDBC 배치가 적용되지 않는다). 생성 컬럼 due_month는 매핑하지 않는다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-10-05
 */
@Entity
@Table(name = "schedule_line", schema = "fgc", uniqueConstraints = {
        @UniqueConstraint(name = "uq_schedule_line_no", columnNames = {"schedule_header_id", "line_no"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScheduleLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "schedule_line_id")
    private Long scheduleLineId;

    @Column(name = "schedule_header_id", nullable = false)
    private Long scheduleHeaderId;

    @Column(name = "line_no", nullable = false)
    private Integer lineNo;

    @Column(name = "installment_no", nullable = false)
    private Integer installmentNo;

    @Column(name = "contract_month_no", nullable = false)
    private Integer contractMonthNo;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "commission_item_id", nullable = false)
    private Long commissionItemId;

    @Column(name = "beneficiary_agent_id")
    private Long beneficiaryAgentId;

    @Column(name = "basis_code", nullable = false, length = 40)
    private String basisCode;

    @Column(name = "basis_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal basisAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "calculation_type", nullable = false, length = 15)
    private CalculationType calculationType;

    @Column(name = "rate_pct", precision = 9, scale = 6)
    private BigDecimal ratePct;

    @Column(name = "fixed_amount", precision = 15, scale = 2)
    private BigDecimal fixedAmount;

    @Column(name = "expected_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal expectedAmount;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "rounding_scale", nullable = false)
    private Integer roundingScale;

    @Enumerated(EnumType.STRING)
    @Column(name = "rounding_mode", nullable = false, length = 20)
    private RoundingMode roundingMode;

    @Column(name = "payment_condition_code", length = 50)
    private String paymentConditionCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "line_status", nullable = false, length = 20)
    private ScheduleLineStatus lineStatus;

    @Column(name = "source_commission_rule_id")
    private Long sourceCommissionRuleId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
