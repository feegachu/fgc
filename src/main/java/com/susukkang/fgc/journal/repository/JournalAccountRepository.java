package com.susukkang.fgc.journal.repository;

import com.susukkang.fgc.journal.entity.JournalAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 설명 : 활성 원장 계정과목 조회 Repository
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-27
 */
public interface JournalAccountRepository extends JpaRepository<JournalAccount, Long> {

    Optional<JournalAccount> findByAccountCodeAndActiveYnTrue(String accountCode);

    List<JournalAccount> findAllByActiveYnTrueOrderByAccountCodeAsc();
}
