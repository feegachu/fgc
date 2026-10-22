package com.susukkang.fgc.validation.repository;

import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.common.web.ListSort;
import com.susukkang.fgc.validation.dto.ValidationRunListRow;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Transactional(readOnly = true)
public class ValidationRunSearchImpl implements ValidationRunSearch {

    /** 화면 정렬 key(VRUN-W01 컬럼) → JPQL 표현식. 실행 유형·상태·진행·실패 사유는 순서 기준이 없어 제외한다. */
    private static final Map<String, String> SORTABLE = Map.of(
            "validationMonth", "v.validationMonth",
            "runNo", "v.runNo",
            "triggeredBy", "triggered.loginId",
            "startedAt", "v.startedAt",
            "completedAt", "v.completedAt",
            "finalizedAt", "v.finalizedAt");
    public static final Set<String> SORT_KEYS = SORTABLE.keySet();
    public static final ListSort DEFAULT_SORT = new ListSort("validationMonth", false);

    private static final String SELECT = """
            SELECT new com.susukkang.fgc.validation.dto.ValidationRunListRow(
                v.validationRunId, v.validationMonth, v.runNo, v.runType,
                CAST(v.status AS string), v.currentStep,
                triggered.loginId, v.startedAt, v.completedAt,
                finalized.loginId, v.finalizedAt, v.failureMessage)
            FROM ValidationRun v
            LEFT JOIN AppUser triggered ON triggered.userId = v.triggeredBy
            LEFT JOIN AppUser finalized ON finalized.userId = v.finalizedBy
            WHERE (CAST(:month AS LocalDate) IS NULL OR v.validationMonth = :month)
              AND (:status IS NULL OR v.status = :status)
            """;

    private final EntityManager entityManager;

    public ValidationRunSearchImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** 요청 정렬을 검증한다. 허용 key 가 아니면 COMMON_002. 비어 있으면 검증월·회차 내림차순. */
    public static ListSort sortOf(String raw) {
        return ListSort.parse(raw, SORT_KEYS, DEFAULT_SORT);
    }

    @Override
    public Page<ValidationRunListRow> searchSorted(
            LocalDate month, ValidationRunStatus status, ListSort sort, Pageable pageable) {
        String direction = sort.ascending() ? "ASC" : "DESC";
        // 정렬 표현식은 SORTABLE 의 코드 상수만 쓴다. 값이 비면 방향과 관계없이 맨 뒤로, 동률은 검증월·회차 내림차순으로 고정한다.
        // 검증월 정렬은 같은 달 안에서 회차도 같은 방향으로 따라가야 기존 기본 정렬(검증월·회차 내림차순)과 같다.
        String orderBy = "validationMonth".equals(sort.key())
                ? " ORDER BY v.validationMonth " + direction + ", v.runNo " + direction
                : " ORDER BY " + SORTABLE.get(sort.key()) + " " + direction + " NULLS LAST, v.validationMonth DESC, v.runNo DESC";
        List<ValidationRunListRow> content = entityManager
                .createQuery(SELECT + orderBy, ValidationRunListRow.class)
                .setParameter("month", month)
                .setParameter("status", status)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();
        long total = entityManager.createQuery("""
                        SELECT COUNT(v) FROM ValidationRun v
                        WHERE (CAST(:month AS LocalDate) IS NULL OR v.validationMonth = :month)
                          AND (:status IS NULL OR v.status = :status)
                        """, Long.class)
                .setParameter("month", month)
                .setParameter("status", status)
                .getSingleResult();
        return new PageImpl<>(content, pageable, total);
    }
}
