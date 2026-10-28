package com.susukkang.fgc.compare.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.susukkang.fgc.compare.dto.CompareRankingResponse;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CompareRankResultStoreTest {

    private static CompareRankingResponse response() {
        return new CompareRankingResponse(null, "HEALTH_PROTECTION", "건강보험", LocalDate.of(2026, 7, 31), "GA-CUR-2026-V1 v1",
                100_000L, 12, List.of(), null, List.of());
    }

    @Test
    @DisplayName("본인이 만든 결과는 다시 찾을 수 있다")
    void ownerCanFind() {
        CompareRankResultStore store = new CompareRankResultStore();

        String id = store.save(7L, response());

        assertThat(store.find(7L, id)).isPresent();
    }

    @Test
    @DisplayName("다른 설계사의 결과는 ID를 알아도 찾을 수 없다")
    void otherAgentCannotFind() {
        CompareRankResultStore store = new CompareRankResultStore();

        String id = store.save(7L, response());

        assertThat(store.find(8L, id)).isEmpty();
    }

    @Test
    @DisplayName("없는 ID는 찾을 수 없다")
    void unknownIdIsEmpty() {
        assertThat(new CompareRankResultStore().find(7L, "none")).isEmpty();
    }
}
