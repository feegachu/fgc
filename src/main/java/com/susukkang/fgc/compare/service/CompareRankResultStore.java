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

    private void purgeExpired() {
        Instant limit = Instant.now().minus(TTL);
        entries.values().removeIf(stored -> stored.createdAt().isBefore(limit));
    }
}
