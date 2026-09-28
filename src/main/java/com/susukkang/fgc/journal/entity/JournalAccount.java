package com.susukkang.fgc.journal.entity;

import com.susukkang.fgc.journal.domain.NormalBalance;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 설명 : 내부 검증원장 계정과목 엔티티
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-27
 */
@Entity
@Table(
        name = "journal_account",
        schema = "fgc",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_journal_account_code", columnNames = "account_code")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JournalAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "journal_account_id", nullable = false)
    private Long journalAccountId;

    @Column(name = "account_code", nullable = false, length = 40)
    private String accountCode;

    @Column(name = "account_name", nullable = false, length = 120)
    private String accountName;

    @Enumerated(EnumType.STRING)
    @Column(name = "normal_balance", nullable = false, length = 10)
    private NormalBalance normalBalance;

    @Column(name = "active_yn", nullable = false)
    private boolean activeYn = true;
}
