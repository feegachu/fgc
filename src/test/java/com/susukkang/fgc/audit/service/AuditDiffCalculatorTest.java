package com.susukkang.fgc.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.audit.dto.AuditDiffEntry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AUDT-W01 변경 내용 비교 단위 테스트 (#407).
 * 1차 AuditLogViewControllerTest 가 HTML 로 보던 diff 규칙을 API 가 쓰는 계산기에서 직접 본다.
 */
class AuditDiffCalculatorTest {

    private final AuditDiffCalculator calculator = new AuditDiffCalculator(new ObjectMapper());

    @Test
    void comparesNestedLeafPathsAndFlagsOnlyChangedOnes() {
        assertThat(calculator.diff(
                "{\"payment\":{\"amount\":500000,\"status\":\"DRAFT\"},\"attributions\":[{\"contractId\":3}]}",
                "{\"payment\":{\"amount\":700000,\"status\":\"DRAFT\"},\"attributions\":[{\"contractId\":3}]}"))
                .containsExactly(
                        new AuditDiffEntry("payment.amount", "500000", "700000", true),
                        new AuditDiffEntry("payment.status", "DRAFT", "DRAFT", false),
                        new AuditDiffEntry("attributions[0].contractId", "3", "3", false));
    }

    /** 생성 로그(before 없음)·삭제 로그(after 없음)는 오류 없이 한쪽만 채운다. */
    @Test
    void showsOnlyOneSideWhenBeforeOrAfterIsMissing() {
        assertThat(calculator.diff(null, "{\"status\":\"DRAFT\"}"))
                .containsExactly(new AuditDiffEntry("status", null, "DRAFT", true));
        assertThat(calculator.diff("{\"status\":\"DRAFT\"}", " "))
                .containsExactly(new AuditDiffEntry("status", "DRAFT", null, true));
        assertThat(calculator.diff(null, null)).isEmpty();
    }

    @Test
    void addsKeysPresentOnlyAfterChangeAndKeepsEmptyContainersAsLeaves() {
        assertThat(calculator.diff("{\"tags\":[]}", "{\"tags\":[],\"memo\":\"x\"}"))
                .containsExactly(
                        new AuditDiffEntry("tags", "[]", "[]", false),
                        new AuditDiffEntry("memo", null, "x", true));
    }

    /** 한쪽이 JSON 객체가 아니면(파싱 실패 포함) 원문 한 줄 비교 — 원문이 diff 에서 사라지면 안 된다. */
    @Test
    void fallsBackToRawComparisonWhenEitherSideIsNotAJsonObject() {
        assertThat(calculator.diff("not-a-json-object", "{\"status\":\"CONFIRMED\"}"))
                .containsExactly(new AuditDiffEntry("value", "not-a-json-object", "{\"status\":\"CONFIRMED\"}", true));
        assertThat(calculator.diff("{\"status\":\"DRAFT\"}", "[1,2,3]"))
                .containsExactly(new AuditDiffEntry("value", "{\"status\":\"DRAFT\"}", "[1,2,3]", true));
    }
}
