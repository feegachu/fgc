package com.susukkang.fgc.exceptioncase.repository;

import com.susukkang.fgc.exceptioncase.entity.ExceptionAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 설명 : 예외 조치 이력 저장 및 예외별 순번 조회 Repository
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-28
 */
public interface ExceptionActionRepository extends JpaRepository<ExceptionAction, Long> {

    // 같은 예외의 부모 행을 FOR UPDATE로 잠근 트랜잭션에서만 채번·저장한다.
    @Query("""
            SELECT COALESCE(MAX(a.actionSeq), 0) + 1
              FROM ExceptionAction a
             WHERE a.exceptionCaseId = :exceptionCaseId
            """)
    int findNextActionSeq(@Param("exceptionCaseId") Long exceptionCaseId);
}
