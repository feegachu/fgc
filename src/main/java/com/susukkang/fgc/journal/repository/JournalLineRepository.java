package com.susukkang.fgc.journal.repository;

import com.susukkang.fgc.journal.entity.JournalLine;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 설명 : 원장 분개 라인 저장 Repository
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-28
 */
public interface JournalLineRepository extends JpaRepository<JournalLine, Long> {
}
