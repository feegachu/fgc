package com.susukkang.fgc.news.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.susukkang.fgc.news.dto.NewsHeadline;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NewsHeadlineServiceTest {

    private static byte[] rss(String items) {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss version=\"2.0\"><channel><title>t</title>" + items
                + "</channel></rss>").getBytes(StandardCharsets.UTF_8);
    }

    private static String item(String title, String link) {
        return "<item><title><![CDATA[" + title + "]]></title><link>" + link
                + "</link><description>본문</description><pubDate>2026-10-29 13:50:29</pubDate></item>";
    }

    @Test
    @DisplayName("제목·링크·날짜만 뽑고 개수 상한을 지킨다")
    void parsesTitleLinkAndDateWithLimit() throws Exception {
        byte[] xml = rss(item("첫째 기사", "https://www.insjournal.co.kr/news/articleView.html?idxno=1")
                + item("둘째 기사", "https://www.insjournal.co.kr/news/articleView.html?idxno=2")
                + item("셋째 기사", "https://www.insjournal.co.kr/news/articleView.html?idxno=3"));

        List<NewsHeadline> list = NewsHeadlineService.parse(xml, 2);

        assertThat(list).extracting(NewsHeadline::title).containsExactly("첫째 기사", "둘째 기사");
        assertThat(list.get(0).link()).isEqualTo("https://www.insjournal.co.kr/news/articleView.html?idxno=1");
        assertThat(list.get(0).publishedAt()).isEqualTo("2026-10-29 13:50:29");
    }

    @Test
    @DisplayName("허용 도메인의 https 링크가 아니거나 제목이 없는 항목은 버린다")
    void dropsUnsafeOrEmptyItems() throws Exception {
        byte[] xml = rss(item("외부 사이트", "https://evil.example.com/a")
                + item("http 링크", "http://www.insjournal.co.kr/a")
                + item("스크립트", "javascript:alert(1)")
                + item("계정 정보 포함", "https://user@www.insjournal.co.kr/a")
                + item("   ", "https://www.insjournal.co.kr/a")
                + item("정상", "https://www.insjournal.co.kr/a"));

        assertThat(NewsHeadlineService.parse(xml, 5)).extracting(NewsHeadline::title).containsExactly("정상");
    }

    @Test
    @DisplayName("긴 제목은 잘라 내고 공백을 정리한다")
    void trimsLongTitle() throws Exception {
        String longTitle = "가".repeat(200);
        List<NewsHeadline> list = NewsHeadlineService.parse(rss(item("  앞  뒤 \n" + longTitle, "https://www.insjournal.co.kr/a")), 5);

        assertThat(list.get(0).title()).hasSize(NewsHeadlineService.MAX_TITLE_CHARS).endsWith("…").startsWith("앞 뒤 ");
    }

    @Test
    @DisplayName("DOCTYPE(외부 엔티티)이 있는 XML 은 거부한다")
    void rejectsDoctype() {
        byte[] xml = "<?xml version=\"1.0\"?><!DOCTYPE rss [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><rss><channel><item><title>&x;</title></item></channel></rss>"
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> NewsHeadlineService.parse(xml, 5)).isInstanceOf(Exception.class);
    }
}
