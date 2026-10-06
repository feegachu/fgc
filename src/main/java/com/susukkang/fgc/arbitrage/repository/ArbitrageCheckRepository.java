package com.susukkang.fgc.arbitrage.repository;

import com.susukkang.fgc.arbitrage.entity.ArbitrageCheck;
import org.springframework.data.jpa.repository.JpaRepository;

/** 기본 결과 조회는 JPA, 집계·최신 결과·원자적 UPSERT는 전용 쿼리 fragment가 담당한다. */
public interface ArbitrageCheckRepository extends JpaRepository<ArbitrageCheck, Long>, ArbitrageCheckQueries {
}
