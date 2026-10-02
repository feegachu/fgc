package com.susukkang.fgc.cap.repository;

import com.susukkang.fgc.cap.entity.CapCheckDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 한도 결과 상세의 기본 저장·조회와 재계산 후 초과 행 정리 계약. */
public interface CapCheckDetailRepository extends JpaRepository<CapCheckDetail, Long> {
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from CapCheckDetail detail where detail.capCheckId = :capCheckId and detail.detailSeq > :maxDetailSeq")
    int deleteObsoleteDetails(@Param("capCheckId") Long capCheckId, @Param("maxDetailSeq") int maxDetailSeq);
}
