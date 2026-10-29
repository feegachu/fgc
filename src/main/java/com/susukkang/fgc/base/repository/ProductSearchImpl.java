package com.susukkang.fgc.base.repository;

import com.susukkang.fgc.base.dto.ProductRow;
import com.susukkang.fgc.common.web.ListSort;
import com.susukkang.fgc.common.web.SqlSort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Transactional(readOnly = true)
public class ProductSearchImpl implements ProductSearch {

    private static final String DEFAULT_TIE_BREAKER =
            "p.insurer_product_code, po.offering_version, po.channel_code, po.product_offering_id";

    /** 화면 정렬 key(기준정보 상품 탭) → SQL 표현식. 상품군·수수료체계·채널·80% 공제는 코드값이라 순서 기준이 없어 제외한다. */
    private static final Map<String, String> SORTABLE = Map.of(
            "insurerProductCode", "p.insurer_product_code",
            "productName", "p.product_name",
            "standardProductCode", "p.standard_product_code",
            "offeringVersion", "po.offering_version",
            "salesStartDate", "po.sales_start_date",
            "basicDocumentDate", "po.basic_document_date");
    public static final Set<String> SORT_KEYS = Set.copyOf(SORTABLE.keySet());

    private final EntityManager entityManager;

    public ProductSearchImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** 요청 정렬을 검증한다. 허용 key 가 아니면 COMMON_002. 비어 있으면 null — 기존 상품코드 순. */
    public static ListSort sortOf(String raw) {
        return ListSort.parse(raw, SORT_KEYS, null);
    }

    // S2077: ORDER BY 는 SORTABLE 의 코드 상수 표현식만 쓰고(요청 key 는 ListSort 가 허용 목록으로 먼저 걸러 낸다), 값은 모두 setParameter 로 바인딩한다.
    @SuppressWarnings({"unchecked", "java:S2077"})
    @Override
    public Page<ProductRow> searchSorted(long insurerId, LocalDate asOf, boolean includeExpired,
                                         boolean includeHistorical, ListSort sort, Pageable pageable) {
        return queryPage(insurerId, asOf, includeExpired, includeHistorical, null, null, sort, pageable);
    }

    @Override
    public Page<ProductRow> searchSelectable(long insurerId, LocalDate asOf, boolean includeExpired,
                                            boolean includeHistorical, String keyword, Long selectedId, ListSort sort, Pageable pageable) {
        return queryPage(insurerId, asOf, includeExpired, includeHistorical, keyword, selectedId, sort, pageable);
    }

    @SuppressWarnings({"unchecked", "java:S2077"})
    private Page<ProductRow> queryPage(long insurerId, LocalDate asOf, boolean includeExpired,
                                      boolean includeHistorical, String keyword, Long selectedId, ListSort sort, Pageable pageable) {
        String filter = """
                AND (CAST(:selectedId AS bigint) IS NULL OR po.product_offering_id = :selectedId)
                AND (:keyword = '' OR strpos(lower(p.product_name), lower(:keyword)) > 0
                  OR strpos(lower(p.insurer_product_code), lower(:keyword)) > 0
                  OR strpos(lower(p.standard_product_code), lower(:keyword)) > 0
                  OR strpos(lower(po.offering_version), lower(:keyword)) > 0)
                """;
        // 정렬 표현식은 SORTABLE 의 코드 상수만 쓴다. 값이 비면 방향과 관계없이 맨 뒤로, 동률은 기존 기본 정렬을 따른다.
        String orderBy = sort == null
                ? ProductSearchSql.DEFAULT_ORDER
                : SqlSort.orderBy(sort, SORTABLE, DEFAULT_TIE_BREAKER) + "\n";
        Query query = entityManager.createNativeQuery(ProductSearchSql.BODY + filter + orderBy, "ProductRowMapping");
        bind(query, insurerId, asOf, includeExpired, includeHistorical);
        query.setParameter("keyword", keyword == null ? "" : keyword.trim()).setParameter("selectedId", selectedId);
        query.setFirstResult((int) pageable.getOffset()).setMaxResults(pageable.getPageSize());
        List<ProductRow> content = query.getResultList();

        Query count = entityManager.createNativeQuery(ProductSearchSql.COUNT + filter);
        bind(count, insurerId, asOf, includeExpired, includeHistorical);
        count.setParameter("keyword", keyword == null ? "" : keyword.trim()).setParameter("selectedId", selectedId);
        long total = ((Number) count.getSingleResult()).longValue();
        return new PageImpl<>(content, pageable, total);
    }

    private static void bind(Query query, long insurerId, LocalDate asOf, boolean includeExpired, boolean includeHistorical) {
        query.setParameter("insurerId", insurerId);
        query.setParameter("asOf", asOf);
        query.setParameter("includeExpired", includeExpired);
        query.setParameter("includeHistorical", includeHistorical);
    }
}
