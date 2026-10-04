package com.susukkang.fgc.reconciliation.repository;

import com.susukkang.fgc.reconciliation.entity.ReconciliationMatch;
import org.springframework.data.jpa.repository.JpaRepository;

/** 대사 매칭 원천 엔티티 조회 계약. 멱등 쓰기는 ReconciliationResultRepository가 소유한다. */
public interface ReconciliationMatchJpaRepository extends JpaRepository<ReconciliationMatch, Long> {
}
