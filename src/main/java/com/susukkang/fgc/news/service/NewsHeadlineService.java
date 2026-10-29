package com.susukkang.fgc.news.service;

import com.susukkang.fgc.news.dto.NewsHeadline;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * 보험 업계 최신 뉴스 헤드라인(제목·원문 링크만). 고정된 RSS 주소 한 곳에서만 읽고,
 * 결과는 잠깐 캐시한다. 실패해도 예외를 밖으로 내보내지 않고 직전 결과(없으면 빈 목록)를 돌려준다.
 */
@Slf4j
@Service
public class NewsHeadlineService {

    static final int MAX_TITLE_CHARS = 120;
    private static final int MAX_BODY_BYTES = 2_000_000;
    private static final List<String> ALLOWED_HOSTS = List.of("insjournal.co.kr", "www.insjournal.co.kr");

    private final String feedUrl;
    private final int maxItems;
    private final Duration ttl;
    private final Duration failureBackoff;
    private final Duration timeout;
    private final HttpClient client;

    private List<NewsHeadline> cached = List.of();
    private Instant nextFetchAt = Instant.EPOCH;

    public NewsHeadlineService(
            @Value("${fgc.news.feed-url:https://www.insjournal.co.kr/rss/allArticle.xml}") String feedUrl,
            @Value("${fgc.news.max-items:5}") int maxItems,
            @Value("${fgc.news.cache-ttl:10m}") Duration ttl,
            @Value("${fgc.news.failure-backoff:2m}") Duration failureBackoff,
            @Value("${fgc.news.timeout:5s}") Duration timeout) {
        this.feedUrl = feedUrl;
        this.maxItems = Math.clamp(maxItems, 1, 20);
        this.ttl = ttl;
        this.failureBackoff = failureBackoff;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public synchronized List<NewsHeadline> latest() {
        Instant now = Instant.now();
        if (now.isBefore(nextFetchAt)) {
            return cached;
        }
        try {
            List<NewsHeadline> fresh = parse(fetch(), maxItems);
            if (!fresh.isEmpty()) {
                cached = fresh;
            }
            nextFetchAt = now.plus(ttl);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            nextFetchAt = now.plus(failureBackoff);
        } catch (Exception e) {
            // 뉴스는 보조 정보다. 실패하면 직전 결과를 유지하고 잠시 다시 부르지 않는다.
            log.warn("뉴스 헤드라인을 불러오지 못했습니다: {}", e.getClass().getSimpleName());
            nextFetchAt = now.plus(failureBackoff);
        }
        return cached;
    }

    private byte[] fetch() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(feedUrl)).timeout(timeout)
                .header("Accept", "application/rss+xml, application/xml, text/xml").GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        if (response.body().length > MAX_BODY_BYTES) {
            throw new IOException("RSS too large");
        }
        return response.body();
    }

    /** RSS 2.0 의 item 에서 제목·링크·날짜만 뽑는다. 허용 도메인의 https 링크가 아닌 항목은 버린다. */
    static List<NewsHeadline> parse(byte[] xml, int max) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        NodeList items = document.getElementsByTagName("item");
        List<NewsHeadline> result = new ArrayList<>();
        for (int i = 0; i < items.getLength() && result.size() < max; i++) {
            Element item = (Element) items.item(i);
            String title = clean(text(item, "title"));
            String link = text(item, "link").trim();
            if (title.isEmpty() || !isAllowedLink(link)) {
                continue;
            }
            if (title.length() > MAX_TITLE_CHARS) {
                title = title.substring(0, MAX_TITLE_CHARS - 1) + "…";
            }
            result.add(new NewsHeadline(title, link, text(item, "pubDate").trim()));
        }
        return List.copyOf(result);
    }

    static boolean isAllowedLink(String link) {
        try {
            URI uri = URI.create(link);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null && uri.getHost() != null
                    && ALLOWED_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String text(Element item, String tag) {
        NodeList nodes = item.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent();
    }

    private static String clean(String raw) {
        return raw.replaceAll("\\s+", " ").trim();
    }
}
