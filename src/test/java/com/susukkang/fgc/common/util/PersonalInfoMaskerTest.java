package com.susukkang.fgc.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PersonalInfoMaskerTest {

    @Test
    void masksThreeCharacterNameKeepingFirstAndLast() {
        assertThat(PersonalInfoMasker.maskName("홍길동")).isEqualTo("홍*동");
    }

    @Test
    void masksLongerNameWithRepeatedAsterisks() {
        assertThat(PersonalInfoMasker.maskName("김정산")).isEqualTo("김*산");
        assertThat(PersonalInfoMasker.maskName("남궁민수")).isEqualTo("남**수");
    }

    @Test
    void twoCharacterNameKeepsFirstCharacterOnly() {
        assertThat(PersonalInfoMasker.maskName("이일")).isEqualTo("이*");
    }

    @Test
    void nullAndBlankPassThroughUnchanged() {
        assertThat(PersonalInfoMasker.maskName(null)).isNull();
        assertThat(PersonalInfoMasker.maskName("")).isEmpty();
    }
}
