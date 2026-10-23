package com.susukkang.fgc.contract.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.Immutable;

import java.time.LocalDate;

/** FUN-085: 기존 V1 담당 이력의 읽기 전용 매핑. 변경 기능은 FUN-010에서 담당한다. */
@Entity
@Table(name = "contract_manager_assignment")
@Immutable
@Getter
public class ContractManagerAssignment {
    @Id
    @Column(name = "contract_manager_assignment_id")
    private Long id;
    @Column(name = "contract_id")
    private Long contractId;
    @Column(name = "agent_id")
    private Long agentId;
    @Column(name = "assignment_type")
    private String assignmentType;
    @Column(name = "effective_from")
    private LocalDate effectiveFrom;
    @Column(name = "effective_to")
    private LocalDate effectiveTo;
}
