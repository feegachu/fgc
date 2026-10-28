package com.susukkang.fgc.compare.service;

import com.susukkang.fgc.compare.dto.CompareRankingResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 설명 : explain 호출이 화면이 보낸 금액·순위를 믿지 않고 서버가 계산한 값을 다시 찾도록, 순위 결과를 설계사별로 잠깐 보관한다.
 * 메모리에만 두고 TTL 이 지나면 버리며, 계약자 개인정보는 들어 있지 않다. 다른 설계사의 ID로는 찾을 수 없다.
 */
@Component
public class CompareRankResultStore {

    static final Duration TTL = Duration.ofMinutes(30);
    private static final int MAX_ENTRIES = 1000;

    private record Stored(long agentId, CompareRankingResponse response, Instant createdAt) {
    }

    private final Map<String, Stored> entries = new ConcurrentHashMap<>();
    /** LLM 으로 만든 요약. 같은 결과를 다시 요청해도 호출·비용이 늘지 않도록 결과와 같은 수명으로 둔다. */
    private final Map<String, String> summaries = new ConcurrentHashMap<>();

    public String save(long agentId, CompareRankingResponse response) {
        purgeExpired();
        if (entries.size() >= MAX_ENTRIES) {
            entries.entrySet().stream().min(java.util.Comparator.comparing(e -> e.getValue().createdAt()))
                    .ifPresent(oldest -> entries.remove(oldest.getKey()));
        }
        String id = UUID.randomUUID().toString();
        entries.put(id, new Stored(agentId, response, Instant.now()));
        return id;
    }

    /** 본인이 만든 결과이고 TTL 안일 때만 돌려준다. */
    public Optional<CompareRankingResponse> find(long agentId, String id) {
        Stored stored = entries.get(id);
        if (stored == null || stored.agentId() != agentId || stored.createdAt().plus(TTL).isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(stored.response());
    }

    /** 다른 설계사가 만든 결과면 true. 없거나 만료된 결과와 구분해 소유권 위반(403)으로 응답하기 위한 것이다. */
    public boolean ownedByOther(long agentId, String id) {
        Stored stored = entries.get(id);
        return stored != null && stored.agentId() != agentId;
    }

    public Optional<String> findSummary(long agentId, String id) {
        return find(agentId, id).map(r -> summaries.get(id));
    }

    public void saveSummary(long agentId, String id, String summary) {
        find(agentId, id).ifPresent(r -> summaries.put(id, summary));
    }

    private void purgeExpired() {
        Instant limit = Instant.now().minus(TTL);
        entries.values().removeIf(stored -> stored.createdAt().isBefore(limit));
        summaries.keySet().retainAll(entries.keySet());
    }
}
