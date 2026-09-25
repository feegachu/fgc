package com.susukkang.fgc.policy.entity;

import com.susukkang.fgc.common.code.PolicySourceClass;
import com.susukkang.fgc.common.code.PolicyStatus;
import com.susukkang.fgc.common.code.PolicyType;
import com.susukkang.fgc.policy.dto.ResolvedCommissionPolicy;
import jakarta.persistence.Column;
import jakarta.persistence.ColumnResult;
import jakarta.persistence.ConstructorResult;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SqlResultSetMapping;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 설명 : 정책 버전 및 적용기간·승인 정보
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-26
 */
@Entity
@SqlResultSetMapping(name = "ResolvedCommissionPolicyMapping", classes = @ConstructorResult(
        targetClass = ResolvedCommissionPolicy.class,
        columns = {
                @ColumnResult(name = "policy_version_id", type = Long.class),
                @ColumnResult(name = "policy_type", type = String.class),
                @ColumnResult(name = "payment_stage", type = String.class),
                @ColumnResult(name = "schedule_regime", type = String.class)
        }
))
@Table(name = "policy_version", uniqueConstraints = {
        @UniqueConstraint(name = "uq_policy_version", columnNames = {"policy_code", "version_no"})
})
@Check(name = "ck_policy_period",
        constraints = "effective_to IS NULL OR effective_to >= effective_from")
@Check(name = "ck_policy_approval",
        constraints = "status NOT IN ('APPROVED', 'ACTIVE', 'RETIRED') OR approved_at IS NOT NULL")
@Check(name = "ck_policy_authorship",
        constraints = "status NOT IN ('APPROVED', 'ACTIVE') "
                + "OR (created_by IS NOT NULL AND approved_by IS NOT NULL "
                + "AND approved_at IS NOT NULL AND created_by <> approved_by)")
@Getter
public class PolicyVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "policy_version_id", nullable = false)
    private Long policyVersionId;

    @Column(name = "policy_code", nullable = false, length = 80)
    private String policyCode;

    @Column(name = "policy_name", nullable = false, length = 200)
    private String policyName;

    @Enumerated(EnumType.STRING)
    @Column(name = "policy_type", nullable = false, length = 40)
    private PolicyType policyType;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_class", nullable = false, length = 30)
    private PolicySourceClass sourceClass;

    @Column(name = "version_no", nullable = false)
    @Check(constraints = "version_no > 0")
    private Integer versionNo;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PolicyStatus status = PolicyStatus.DRAFT;

    @Column(name = "fee_regime_code", length = 40)
    private String feeRegimeCode;

    @Column(name = "basic_document_version", length = 80)
    private String basicDocumentVersion;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "regulation_refs", nullable = false, columnDefinition = "text[]")
    private List<String> regulationRefs = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "source_refs", nullable = false, columnDefinition = "text[]")
    private List<String> sourceRefs = new ArrayList<>();

    @Column(name = "approval_evidence_ref", length = 500)
    private String approvalEvidenceRef;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "created_by")
    private Long createdBy;

    // 생성·수정 시각은 DB 기본값과 trg_policy_version_updated_at 트리거가 관리한다.
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
