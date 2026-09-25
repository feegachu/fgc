package com.susukkang.fgc.base.entity;

import com.susukkang.fgc.base.code.InsurerType;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * 설명 : Insurer Entity
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-24
 */
@Entity
@Table(name = "insurer")
@Getter
public class Insurer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "insurer_id")
    private Long insurerId;

    @Column(name = "insurer_code", nullable = false, length = 30)
    private String insurerCode;

    @Column(name = "insurer_name", nullable = false, length = 120)
    private String insurerName;

    @Enumerated(EnumType.STRING)
    @Column(name = "insurer_type", nullable = false, length = 10)
    private InsurerType insurerType;

    @Column(name = "active_yn", nullable = false)
    private boolean activeYn = true;

    @Column(name = "created_at", nullable = false,
            insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false,
            insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

}
