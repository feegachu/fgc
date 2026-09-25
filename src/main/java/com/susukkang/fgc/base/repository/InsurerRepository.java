package com.susukkang.fgc.base.repository;

import com.susukkang.fgc.base.entity.Insurer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 설명 : InsurerRepository
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-24
 */
public interface InsurerRepository extends JpaRepository<Insurer, Long> {
    @Query("""
        SELECT i
        FROM Insurer i
        WHERE CAST(:keyword AS String) IS NULL
           OR LOWER(i.insurerCode)
              LIKE CONCAT('%', LOWER(CAST(:keyword AS String)), '%')
           OR LOWER(i.insurerName)
              LIKE CONCAT('%', LOWER(CAST(:keyword AS String)), '%')
        """)
    Page<Insurer> search(
            @Param("keyword") String keyword,
            Pageable pageable
    );
}
