package com.susukkang.fgc.compare.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompareRequestLengthTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void 순위_결과_ID와_카테고리_코드는_64자를_넘으면_거부된다() {
        String tooLong = "a".repeat(65);

        assertThat(validator.validate(new CompareAskRequest(tooLong, "왜 1위야?"))).isNotEmpty();
        assertThat(validator.validate(new CompareExplainRequest(tooLong))).isNotEmpty();
        assertThat(validator.validate(new CompareRankingRequest(tooLong, null))).isNotEmpty();
    }

    @Test
    void 서버가_발급한_ID와_보통_카테고리_코드는_통과한다() {
        String issued = UUID.randomUUID().toString();

        assertThat(validator.validate(new CompareAskRequest(issued, "왜 1위야?"))).isEmpty();
        assertThat(validator.validate(new CompareAskRequest(null, "환수가 뭐예요?"))).isEmpty();
        assertThat(validator.validate(new CompareExplainRequest(issued))).isEmpty();
        assertThat(validator.validate(new CompareRankingRequest("HEALTH_PROTECTION", null))).isEmpty();
    }
}
