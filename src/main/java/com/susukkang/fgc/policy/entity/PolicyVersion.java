package com.susukkang.fgc.policy.entity;

import com.susukkang.fgc.common.code.PolicySourceClass;
import com.susukkang.fgc.common.code.PolicyStatus;
import com.susukkang.fgc.common.code.PolicyType;
import com.susukkang.fgc.policy.dto.ResolvedCommissionPolicy;
import com.susukkang.fgc.policy.dto.PolicyDraft;
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
import java.util.LinkedHashSet;

/**
 * 설명 : 정책 버전 및 적용기간·승인 정보
 *
 * @author hjKang
 * @version 1.1
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

    /** 초안의 상태·버전·작성자는 호출한 작성 서비스가 결정한다. */
    public static PolicyVersion draft(PolicyDraft document, int versionNo, Long actorId) {
        PolicyVersion policy = new PolicyVersion();
        policy.policyCode = document.policyCode();
        policy.versionNo = versionNo;
        policy.createdBy = actorId;
        policy.revise(document);
        return policy;
    }

    public void revise(PolicyDraft document) {
        if (status != PolicyStatus.DRAFT || !policyCode.equals(document.policyCode())) {
            throw new IllegalStateException("Only the same policy code's draft can be edited");
        }
        policyName = document.policyName();
        policyType = document.policyType();
        sourceClass = document.sourceClass();
        effectiveFrom = document.effectiveFrom();
        effectiveTo = document.effectiveTo();
        feeRegimeCode = document.feeRegimeCode();
        basicDocumentVersion = document.basicDocumentVersion();
        regulationRefs = new ArrayList<>(new LinkedHashSet<>(document.regulationRefs()));
        sourceRefs = new ArrayList<>(new LinkedHashSet<>(document.sourceRefs()));
    }

    /** 전이 권한·기대 상태·동시성은 작성 서비스가 잠금 안에서 검사한다. */
    public void transition(PolicyStatus nextStatus, Long approverId) {
        transition(nextStatus, approverId, null);
    }

    public void transition(PolicyStatus nextStatus, Long approverId, String evidenceRef) {
        status = nextStatus;
        if (nextStatus == PolicyStatus.APPROVED) {
            approvedBy = approverId;
            approvedAt = OffsetDateTime.now(java.time.ZoneOffset.UTC);
            approvalEvidenceRef = evidenceRef;
        }
    }

    public PolicyVersion cloneDraft(LocalDate from, LocalDate to, int nextVersionNo, Long actorId) {
        PolicyVersion copy = new PolicyVersion();
        copy.policyCode = policyCode;
        copy.policyName = policyName;
        copy.policyType = policyType;
        copy.sourceClass = sourceClass;
        copy.versionNo = nextVersionNo;
        copy.effectiveFrom = from;
        copy.effectiveTo = to;
        copy.feeRegimeCode = feeRegimeCode;
        copy.basicDocumentVersion = basicDocumentVersion;
        copy.regulationRefs = new ArrayList<>(regulationRefs);
        copy.sourceRefs = new ArrayList<>(sourceRefs);
        copy.createdBy = actorId;
        return copy;
    }
}
