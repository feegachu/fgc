package com.susukkang.fgc.common.code;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ContractStatus.nextStatuses()가 운영정책서 제13조의2 1항(D-03, #784)의 허용 전이 15개와
 * 8×8 전 조합에서 같은지 검증한다. 표에 없는 49개(같은 상태 포함)는 모두 거절이어야 한다.
 */
class ContractStatusTest {

    // 운영정책서 제13조의2 1항 표를 그대로 옮긴 기대값. 구현(EnumSet)과 따로 적어 서로 대조한다.
    private static final Set<String> D03_ALLOWED = Set.of(
            "APPLIED→ACTIVE", "APPLIED→CANCELLED",
            "ACTIVE→UNPAID", "ACTIVE→TERMINATED", "ACTIVE→MATURED", "ACTIVE→CANCELLED",
            "UNPAID→ACTIVE", "UNPAID→LAPSED", "UNPAID→TERMINATED", "UNPAID→MATURED", "UNPAID→CANCELLED",
            "LAPSED→REVIVED",
            "REVIVED→UNPAID", "REVIVED→TERMINATED", "REVIVED→MATURED");

    @Test
    // 64개 조합 중 허용은 표의 15개뿐이고 나머지(끝 상태에서 나가는 전이·같은 상태 포함)는 거절한다
    void nextStatusesMatchTheD03TableForAllPairs() {
        assertThat(ContractStatus.values()).hasSize(8);
        Set<String> allowed = new TreeSet<>();
        for (ContractStatus from : ContractStatus.values()) {
            for (ContractStatus to : ContractStatus.values()) {
                if (from.nextStatuses().contains(to)) {
                    allowed.add(from + "→" + to);
                }
            }
        }
        assertThat(allowed).containsExactlyInAnyOrderElementsOf(D03_ALLOWED);
    }
}
