package com.susukkang.fgc.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PublishingTemplateStructureTest {

    private static final List<String> PUBLISHED_TEMPLATES = List.of(
            "templates/base/index.html",
            "templates/contract/form.html",
            "templates/transaction/list.html",
            "templates/transaction/form.html",
            "templates/schedule/detail.html",
            "templates/arbitrage/list.html",
            "templates/ledger/list.html",
            "templates/reco/list.html",
            "templates/exception/list.html",
            "templates/vrun/list.html",
            "templates/vrun/detail.html",
            "templates/audit/list.html"
    );

    @Test
    void publishedScreensUseProductionShellScopeWithoutPrototypeDependencies() throws IOException {
        for (String resource : PUBLISHED_TEMPLATES) {
            String template = resource(resource);

            assertThat(template)
                    .as(resource)
                    .contains("id=\"main-content\"")
                    .contains("publishing-page")
                    .doesNotContain("cdn.tailwindcss.com")
                    .doesNotContain("FGC.SEED");
        }
    }

    @Test
    void reconciliationComparisonKeepsPopupPublishingScope() throws IOException {
        assertThat(resource("templates/reco/compare-modal.html"))
                .contains("th:fragment=\"modal\"")
                .contains("publishing-modal")
                .doesNotContain("<main");
    }

    @Test
    void apiDeferredScreensExposeExplicitPendingStatesWithoutSeedData() throws IOException {
        assertThat(resource("templates/arbitrage/list.html"))
                .contains("조회 API 연동 대기");
        // EXCP-W01 은 #83 에서 안내 배너·상태 필터·목록이 서버 렌더링으로 바인딩됐다 —
        // 아직 미연동인 유형별 요약카드만 대기 상태를 명시한다.
        assertThat(resource("templates/exception/list.html"))
                .contains("유형별 미처리 집계 API 연동 대기");
        assertThat(resource("templates/vrun/detail.html"))
                .contains("진행률 API 연동 대기")
                .contains("확정 조건 API 연동 대기")
                .containsPattern("(?s)<button[^>]*id=\"btn-execute\"[^>]*\\bdisabled\\b[^>]*>");
    }

    @Test
    void fixedBusinessNoticesStayAlignedWithScreenSpecification() throws IOException {
        assertThat(resource("templates/exception/list.html"))
                .contains("정상 건은 여기 오지 않습니다. 여기 있는 건 전부 사람이 봐야 합니다.");
        assertThat(resource("templates/ledger/list.html"))
                .contains("이 원장은 회사의 정식 회계장부가 아닙니다. 정산이 맞는지 확인하려고 FGC가 따로 만드는 보조 장부입니다.");
    }

    @Test
    void productionLayoutLoadsScopedResponsivePublishingStyles() throws IOException {
        assertThat(resource("templates/layout/default.html"))
                .contains("/css/features/publishing.css");

        assertThat(resource("static/css/features/publishing.css"))
                .contains(".app-main > .publishing-page")
                .contains("@media (max-width: 63.9375rem)")
                .contains("@media (max-width: 47.9375rem)")
                .contains("@media (prefers-reduced-motion: reduce)");
    }

    private static String resource(String path) throws IOException {
        try (var input = PublishingTemplateStructureTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
