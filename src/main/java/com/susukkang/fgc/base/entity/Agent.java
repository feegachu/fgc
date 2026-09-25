package com.susukkang.fgc.base.entity;

import com.susukkang.fgc.common.code.AgentRankCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import org.hibernate.annotations.Check;

import java.time.LocalDate;

/**
 * 설명 : Agent
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-25
 */
@Entity
@Table(name = "agent", uniqueConstraints = {
        @UniqueConstraint(name = "uq_agent_code", columnNames = "agent_code")
})
@Check(name = "ck_agent_term",
        constraints = "termination_date IS NULL OR termination_date >= appointment_date")
@Check(name = "ck_agent_newcomer_end",
        constraints = "newcomer_support_end_date IS NULL OR latest_registration_date IS NULL "
                + "OR newcomer_support_end_date >= latest_registration_date")
@Getter
public class Agent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "agent_code", nullable = false, length = 40)
    private String agentCode;

    @Column(name = "agent_name", nullable = false, length = 100)
    private String agentName;

    @Enumerated(EnumType.STRING)
    @Column(name = "rank_code", length = 30)
    private AgentRankCode rankCode;

    @Column(name = "appointment_date", nullable = false)
    private LocalDate appointmentDate;

    @Column(name = "termination_date")
    private LocalDate terminationDate;

    @Column(name = "agent_status", nullable = false, length = 20)
    @Check(constraints = "agent_status IN ('ACTIVE', 'INACTIVE', 'TERMINATED')")
    private String agentStatus;

    @Column(name = "latest_registration_date")
    private LocalDate latestRegistrationDate;

    @Column(name = "prior_three_year_experience_yn")
    private Boolean priorThreeYearExperienceYn;

    @Column(name = "experience_checked_on")
    private LocalDate experienceCheckedOn;

    @Column(name = "newcomer_support_eligible_yn", nullable = false)
    private boolean newcomerSupportEligibleYn;

    @Column(name = "newcomer_support_end_date")
    private LocalDate newcomerSupportEndDate;

    @Column(name = "active_yn", nullable = false)
    private boolean activeYn = true;

}
