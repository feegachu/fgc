package com.susukkang.fgc.policy.dto;

import com.susukkang.fgc.policy.entity.PolicyVersion;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * POL-W01 정책 버전 목록·상세 헤더 1행. policy_version + app_user(작성/승인자 login_id) 조인 투영
 */
@Getter
@Setter
public class PolicyVersionRow {
    private Long policyVersionId;
    private String policyCode;
    private String policyName;
    private String policyType;
    private String sourceClass;
    private Integer versionNo;
    private String status;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private List<String> regulationRefs;
    private List<String> sourceRefs;
    private String createdBy;
    private String approvedBy;
    private OffsetDateTime approvedAt;

    public static PolicyVersionRow from(PolicyVersion policy, String createdBy, String approvedBy) {
        PolicyVersionRow row = new PolicyVersionRow();
        row.setPolicyVersionId(policy.getPolicyVersionId());
        row.setPolicyCode(policy.getPolicyCode());
        row.setPolicyName(policy.getPolicyName());
        row.setPolicyType(policy.getPolicyType().name());
        row.setSourceClass(policy.getSourceClass().name());
        row.setVersionNo(policy.getVersionNo());
        row.setStatus(policy.getStatus().name());
        row.setEffectiveFrom(policy.getEffectiveFrom());
        row.setEffectiveTo(policy.getEffectiveTo());
        row.setRegulationRefs(policy.getRegulationRefs());
        row.setSourceRefs(policy.getSourceRefs());
        row.setCreatedBy(createdBy);
        row.setApprovedBy(approvedBy);
        row.setApprovedAt(policy.getApprovedAt());
        return row;
    }
}
