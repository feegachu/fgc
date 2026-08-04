package com.susukkang.fgc.common.web;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageResponseTest {

    @Test
    void calculatesTotalPagesFromTotalElements() {
        PageResponse<String> response = PageResponse.of(
                List.of("A", "B"),
                1,
                10,
                21,
                "id,desc"
        );

        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.totalElements()).isEqualTo(21);
    }

    @Test
    void storesContentAsImmutableCopy() {
        List<String> content = new ArrayList<>(List.of("A"));

        PageResponse<String> response = PageResponse.of(
                content,
                1,
                10,
                1,
                null
        );
        content.add("B");

        assertThat(response.content()).containsExactly("A");
        assertThatThrownBy(() -> response.content().add("C"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsPageAndSizeOutsideAllowedRange() {
        assertThatThrownBy(() -> PageResponse.of(List.of(), 0, 10, 0, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> PageResponse.of(List.of(), 1, 101, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
