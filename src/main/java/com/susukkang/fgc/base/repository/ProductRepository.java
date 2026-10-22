package com.susukkang.fgc.base.repository;

import com.susukkang.fgc.base.dto.ProductRow;
import com.susukkang.fgc.base.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

/**
 * 설명 : 상품 판매버전 및 적용 환급률표의 납입기간을 조회하는 Repository
 *
 * @author hjKang
 * @version 1.1
 * @since 2026-09-26
 */
public interface ProductRepository extends JpaRepository<Product, Long>, ProductSearch {

    // 납입기간은 판매버전 ID가 아닌 상품·채널별 최신 활성 환수율표에서 가져온다.
    @NativeQuery(value = ProductSearchSql.BODY + ProductSearchSql.DEFAULT_ORDER,
            countQuery = ProductSearchSql.COUNT,
            sqlResultSetMapping = "ProductRowMapping")
    Page<ProductRow> search(
            @Param("insurerId") long insurerId,
            @Param("asOf") LocalDate asOf,
            @Param("includeExpired") boolean includeExpired,
            @Param("includeHistorical") boolean includeHistorical,
            Pageable pageable
    );

    default Page<ProductRow> search(long insurerId, LocalDate asOf, boolean includeExpired, Pageable pageable) {
        return search(insurerId, asOf, includeExpired, false, pageable);
    }
}
