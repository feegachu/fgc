package com.susukkang.fgc.reconciliation.entity;

import com.susukkang.fgc.reconciliation.domain.ReconciliationResultType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 설명 : 실행·매칭키별 대사 결과 및 감사 snapshot 매핑.
 * 멱등 저장은 결과 저장소의 ON CONFLICT로 원본 snapshot을 보존한다.
 *
 * @author C4t4ddict
 * @since 2026-10-05
 * @version 1.0
 */
@Entity
@Table(name = "reconciliation_result", schema = "fgc")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconciliationResult {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reconciliation_result_id")
    private Long reconciliationResultId;
    @Column(name = "reconciliation_run_id", nullable = false)
    private Long reconciliationRunId;
    @Column(name = "match_group_key", nullable = false, length = 300)
    private String matchGroupKey;
    @Column(name = "contract_id")
    private Long contractId;
    @Column(name = "expected_agent_id")
    private Long expectedAgentId;
    @Column(name = "actual_agent_id")
    private Long actualAgentId;
    @Column(name = "actual_source_agent_code", length = 80)
    private String actualSourceAgentCode;
    @Column(name = "commission_item_id")
    private Long commissionItemId;
    @Column(name = "installment_no")
    private Integer installmentNo;
    @Enumerated(EnumType.STRING)
    @Column(name = "result_type", nullable = false, length = 30)
    private ReconciliationResultType resultType;
    @Column(name = "expected_total_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal expectedTotalAmount;
    @Column(name = "actual_total_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal actualTotalAmount;
    @Column(name = "difference_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal differenceAmount;
    @Column(name = "primary_reason_code", length = 50)
    private String primaryReasonCode;
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "secondary_reason_codes", nullable = false, columnDefinition = "text[]")
    private String[] secondaryReasonCodes;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail_snapshot", nullable = false, columnDefinition = "jsonb")
    private String detailSnapshot;
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
