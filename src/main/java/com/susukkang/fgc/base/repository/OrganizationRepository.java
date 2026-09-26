package com.susukkang.fgc.base.repository;

import com.susukkang.fgc.base.dto.OrganizationRow;
import com.susukkang.fgc.base.entity.Organization;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

/**
 * 설명 : 조직 기준정보 및 적용기간별 유효 조직을 조회하는 Repository
 *
 * @author hjKang
 * @version 1.1
 * @since 2026-09-26
 */
public interface OrganizationRepository extends JpaRepository<Organization,Long> {
    @Query(value = """
        SELECT new com.susukkang.fgc.base.dto.OrganizationRow(
                               o.organizationId,
                               o.organizationCode,
                               o.organizationName,
                               CAST(o.organizationType AS String),
                               o.parentId,
                               parent.organizationName,
                               o.effectiveFrom,
                               o.effectiveTo,
                               o.activeYn
                           )
        FROM Organization o
        LEFT JOIN Organization parent
            ON parent.organizationId = o.parentId
        WHERE o.effectiveFrom <= :asOf
              AND (o.effectiveTo IS NULL OR o.effectiveTo >= :asOf)
              AND (
                  CAST(:keyword AS String) IS NULL
                  OR LOWER(o.organizationCode)
                     LIKE CONCAT('%', LOWER(CAST(:keyword AS String)), '%')
                  OR LOWER(o.organizationName)
                     LIKE CONCAT('%', LOWER(CAST(:keyword AS String)), '%')
              )
        """,
            countQuery = """
            SELECT COUNT(o)
            FROM Organization o
            WHERE o.effectiveFrom <= :asOf
              AND (o.effectiveTo IS NULL OR o.effectiveTo >= :asOf)
              AND (
                  CAST(:keyword AS String) IS NULL
                  OR LOWER(o.organizationCode)
                     LIKE CONCAT('%', LOWER(CAST(:keyword AS String)), '%')
                  OR LOWER(o.organizationName)
                     LIKE CONCAT('%', LOWER(CAST(:keyword AS String)), '%')
              )
        """
    )
    Page<OrganizationRow> search(
            @Param("keyword") String keyword,
            @Param("asOf") LocalDate asOf,
            Pageable pageable
    );
}
