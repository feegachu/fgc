package com.susukkang.fgc.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 설명 : 인증 사용자 조회에 사용하는 공용 역할 매핑. 소유 영역: A / #369.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-26
 */
@Entity
@Table(name = "app_role")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppRole {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "role_id")
    private Long roleId;

    @Column(name = "role_code", nullable = false, length = 40)
    private String roleCode;

    @Column(name = "role_name", nullable = false, length = 100)
    private String roleName;

    @Column(name = "description", length = 500)
    private String description;
}
