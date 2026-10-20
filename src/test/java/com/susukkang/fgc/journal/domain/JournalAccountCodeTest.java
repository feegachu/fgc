package com.susukkang.fgc.journal.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JournalAccountCodeTest {

    @Test
    void displayNamePrefersServerKoreanName() {
        assertThat(JournalAccountCode.displayName("EXPECTED_RECEIVABLE", " 과거 미수금 ")).isEqualTo("과거 미수금");
    }

    @Test
    void displayNameFallsBackToKnownKoreanNameWhenNameIsMissing() {
        assertThat(JournalAccountCode.displayName("EXPECTED_RECEIVABLE", null)).isEqualTo("예상 수수료미수금");
        assertThat(JournalAccountCode.displayName("CONFIRMED_PAYOUT_PAYABLE", "  ")).isEqualTo("확정 지급채무");
    }

    @Test
    void displayNameNeverExposesUnknownEnglishCode() {
        assertThat(JournalAccountCode.displayName("LEGACY_UNKNOWN", "")).isEqualTo("계정명 확인 필요");
        assertThat(JournalAccountCode.displayName(null, null)).isEqualTo("-");
    }
}
