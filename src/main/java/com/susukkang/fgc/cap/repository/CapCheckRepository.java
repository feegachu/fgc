package com.susukkang.fgc.cap.repository;

import com.susukkang.fgc.cap.entity.CapCheck;
import org.springframework.data.jpa.repository.JpaRepository;

/** 한도 결과 엔티티의 기본 저장·조회 계약. */
public interface CapCheckRepository extends JpaRepository<CapCheck, Long> {


}
