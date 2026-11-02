package com.susukkang.fgc.compare.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompareRankingFiltersTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void insurerIds_null_원소는_검증에서_거부된다() {
        CompareRankingFilters filters = new CompareRankingFilters(Arrays.asList(null, 1L), false);

        assertThat(validator.validate(filters)).isNotEmpty();
    }

    @Test
    void insurerIds_정상_값은_통과하고_정규화된다() {
        CompareRankingFilters filters = new CompareRankingFilters(List.of(3L, 1L, 3L), null);

        assertThat(validator.validate(filters)).isEmpty();
        assertThat(filters.normalized().insurerIds()).containsExactly(1L, 3L);
    }
}
