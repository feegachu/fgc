package com.susukkang.fgc.policy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * 설명 : 한도 룰셋에 속한 수수료 항목별 산입·제외·검토 판정.
 *
 * @author hjKang
 * @version 1.1
 * @since 2026-09-26
 */
@Entity
@Table(name = "cap_rule_item", uniqueConstraints = {
        @UniqueConstraint(name = "uq_cap_rule_item", columnNames = {"cap_rule_set_id", "commission_item_id"})
})
@Getter
public class CapRuleItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cap_rule_item_id", nullable = false)
    private Long capRuleItemId;

    @Column(name = "cap_rule_set_id", nullable = false)
    private Long capRuleSetId;

    @Column(name = "commission_item_id", nullable = false)
    private Long commissionItemId;

    @Column(name = "inclusion_status", nullable = false, length = 20)
    private String inclusionStatus;

    @Column(name = "exclusion_type", length = 40)
    private String exclusionType;

    @Column(name = "evidence_required_yn", nullable = false)
    private boolean evidenceRequiredYn;

    @Column(name = "attribution_method", nullable = false, length = 40)
    private String attributionMethod;

    @Column(name = "allocation_policy_id")
    private Long allocationPolicyId;

    @Column(name = "decision_reason", nullable = false, length = 1000)
    private String decisionReason;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
