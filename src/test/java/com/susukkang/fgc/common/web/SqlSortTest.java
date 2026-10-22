package com.susukkang.fgc.common.web;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SqlSortTest {
    private static final Map<String, String> SORTABLE = Map.of("amount", "ct.amount");

    @Test
    void buildsOrderByFromAllowlistedExpressionWithStableTieBreaker() {
        assertThat(SqlSort.orderBy(new ListSort("amount", false), SORTABLE, "ct.id DESC"))
                .isEqualTo("ORDER BY ct.amount DESC NULLS LAST, ct.id DESC");
        assertThat(SqlSort.orderBy(new ListSort("amount", true), SORTABLE, "ct.id DESC"))
                .isEqualTo("ORDER BY ct.amount ASC NULLS LAST, ct.id DESC");
    }

    @Test
    void usesOnlyTheTieBreakerForTheDefaultOrder() {
        assertThat(SqlSort.orderBy(new ListSort("id", false), SORTABLE, "ct.id DESC")).isEqualTo("ORDER BY ct.id DESC");
    }
}
