package com.susukkang.fgc.common.web;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListSortTest {
    private static final Set<String> ALLOWED = Set.of("name", "amount");
    private static final ListSort DEFAULT = new ListSort("name", false);

    @Test
    void usesDefaultWhenMissingOrBlank() {
        assertThat(ListSort.parse(null, ALLOWED, DEFAULT)).isEqualTo(DEFAULT);
        assertThat(ListSort.parse("  ", ALLOWED, DEFAULT)).isEqualTo(DEFAULT);
    }

    @Test
    void parsesAllowedKeyAndDirection() {
        ListSort sort = ListSort.parse("amount,ASC", ALLOWED, DEFAULT);
        assertThat(sort).isEqualTo(new ListSort("amount", true));
        assertThat(sort.value()).isEqualTo("amount,asc");
        assertThat(ListSort.parse("name,desc", ALLOWED, DEFAULT).ascending()).isFalse();
    }

    @Test
    void rejectsUnknownKeyBadDirectionAndMalformedValue() {
        for (String raw : new String[] {"password,asc", "amount,up", "amount", "amount,asc,extra", ",asc",
                "amount; DROP TABLE x,asc"}) {
            assertThatThrownBy(() -> ListSort.parse(raw, ALLOWED, DEFAULT))
                    .isInstanceOfSatisfying(FgcBusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.COMMON_002));
        }
    }
}
