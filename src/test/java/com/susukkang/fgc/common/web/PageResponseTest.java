package com.susukkang.fgc.common.web;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageResponseTest {

    @Test
    void 전체_건수로_전체_페이지_수를_계산한다() {
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
    void content는_변경할_수_없는_복사본으로_보관한다() {
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
    void 페이지와_크기가_허용_범위를_벗어나면_예외가_발생한다() {
        assertThatThrownBy(() -> PageResponse.of(List.of(), 0, 10, 0, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> PageResponse.of(List.of(), 1, 101, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
