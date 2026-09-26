package com.susukkang.fgc.base.entity;

import com.susukkang.fgc.base.code.OrganizationType;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.LocalDate;

/**
 * 설명 : 조직 기준정보 및 적용기간을 매핑하는 엔티티
 *
 * @author hjKang
 * @version 1.1
 * @since 2026-09-26
 */
@Entity
@Table(name = "organization")
@Getter
public class Organization {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "organization_id")
    private Long organizationId;
    @Column(name = "organization_code")
    private String organizationCode;
    @Column(name = "organization_name")
    private String organizationName;
    @Enumerated(EnumType.STRING)
    @Column(name = "organization_type", nullable = false, length = 20)
    private OrganizationType organizationType;
    @Column(name = "parent_id")
    private Long parentId;
    @Column(name = "effective_from")
    private LocalDate effectiveFrom;
    @Column(name = "effective_to")
    private LocalDate effectiveTo;
    @Column(name = "active_yn")
    private boolean activeYn;
}
