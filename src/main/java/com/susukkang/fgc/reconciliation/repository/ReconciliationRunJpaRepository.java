package com.susukkang.fgc.reconciliation.repository;

import com.susukkang.fgc.reconciliation.entity.ReconciliationRun;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 설명 : 대사 실행 엔티티의 기본 영속성 계약.
 *
 * @author C4t4ddict
 * @since 2026-10-05
 * @version 1.0
 */
public interface ReconciliationRunJpaRepository extends JpaRepository<ReconciliationRun, Long> {
}
