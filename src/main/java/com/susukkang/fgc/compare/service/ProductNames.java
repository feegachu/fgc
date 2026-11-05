package com.susukkang.fgc.compare.service;

import com.susukkang.fgc.compare.dto.CompareRankingResponse.Item;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 설명 : 글(질문·AI 답)에서 순위 결과의 상품 이름을 찾는 규칙 하나(FUN-079).
 * 질문이 가리킨 상품과 AI 답이 말한 상품을 같은 규칙으로 찾아야 서버 문장과 AI 문장이 같은 상품을 다루는지 판단할 수 있다.
 * <ul>
 *   <li>공백은 무시한다. 전체 이름('가상 든든 건강보험 C')을 적으면 그 상품이다.</li>
 *   <li>설계사는 '든든 건강보험'처럼 줄여 부르므로 '가상' 접두어와 끝의 한 글자 구분자를 뗀 이름도 인정한다.
 *       단 다른 상품과 겹치거나 카테고리 화면 이름(정기보험 등)과 같으면 인정하지 않는다.</li>
 *   <li>긴 이름부터 맞추고 맞은 부분은 다시 쓰지 않는다. '변액 연금저축보험' 안의 '연금저축보험'은 따로 맞지 않는다.</li>
 *   <li>보험사만 다르고 이름이 같은 상품은 '보험사 상품' 꼴로 붙여 쓴 경우만 그 상품이고, 이름만 쓰면 어느 상품으로도 읽지 않는다.</li>
 * </ul>
 */
final class ProductNames {

    /** 상품 하나를 가리킨 곳. at 은 원래 글에서 처음 나온 위치다. */
    record Mention(int rank, int at) {
    }

    /** 카테고리 화면 이름. 줄여 부른 상품 이름이 이것과 같으면 카테고리를 말한 것인지 상품을 말한 것인지 알 수 없다. */
    private static final Set<String> CATEGORY_NAMES = CompareRankingService.CATEGORY_LABELS.values().stream()
            .map(ProductNames::squeeze).collect(Collectors.toUnmodifiableSet());

    private record Candidate(int rank, String token, boolean ambiguous) {
    }

    private ProductNames() {
    }

    /** 글에서 찾은 상품. 긴 이름으로 맞은 것부터 순서대로, 순위마다 한 번만 담는다. */
    static List<Mention> find(List<Item> items, String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        // 공백을 뺀 글과, 그 글자마다 원래 글에서의 위치.
        StringBuilder squeezed = new StringBuilder();
        List<Integer> origin = new ArrayList<>();
        for (int i = 0; i < normalized.length(); i++) {
            if (!Character.isWhitespace(normalized.charAt(i))) {
                squeezed.append(normalized.charAt(i));
                origin.add(i);
            }
        }
        String s = squeezed.toString();
        boolean[] used = new boolean[s.length()];

        List<Candidate> candidates = new ArrayList<>();
        for (Item item : items) {
            String full = fullName(item.product());
            String key = productKey(item.product());
            boolean fullUnique = items.stream().filter(o -> full.equals(fullName(o.product()))).count() == 1;
            boolean keyUnique = items.stream().filter(o -> key.equals(productKey(o.product()))).count() == 1;
            if (full.length() >= 2) {
                if (!fullUnique) {
                    candidates.add(new Candidate(item.rank(), fullName(item.insurer()) + full, false));
                }
                candidates.add(new Candidate(item.rank(), full, !fullUnique));
            }
            if (key.length() >= 2 && !key.equals(full) && keyUnique && !CATEGORY_NAMES.contains(key)) {
                candidates.add(new Candidate(item.rank(), key, false));
            }
        }
        candidates.sort(Comparator.comparingInt((Candidate c) -> c.token().length()).reversed());

        Map<Integer, Integer> firstAt = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            int from = 0;
            int found;
            while ((found = s.indexOf(c.token(), from)) >= 0) {
                int end = found + c.token().length();
                from = found + 1;
                if (overlaps(used, found, end)) {
                    continue;
                }
                for (int i = found; i < end; i++) {
                    used[i] = true;
                }
                if (!c.ambiguous()) {
                    firstAt.merge(c.rank(), origin.get(found), Math::min);
                }
            }
        }
        List<Mention> mentions = new ArrayList<>();
        firstAt.forEach((rank, at) -> mentions.add(new Mention(rank, at)));
        return mentions;
    }

    private static boolean overlaps(boolean[] used, int from, int to) {
        for (int i = from; i < to; i++) {
            if (used[i]) {
                return true;
            }
        }
        return false;
    }

    private static String fullName(String name) {
        return name == null ? "" : squeeze(Normalizer.normalize(name.strip(), Normalizer.Form.NFKC));
    }

    /** '가상' 접두어와 끝의 한 글자 구분자(A·B·C)를 떼고 공백 없이 쓴 이름. */
    private static String productKey(String product) {
        if (product == null) {
            return "";
        }
        String name = Normalizer.normalize(product.strip(), Normalizer.Form.NFKC)
                .replaceFirst("^가상\\s*", "").replaceFirst("\\s+[A-Za-z]$", "");
        return squeeze(name);
    }

    private static String squeeze(String text) {
        return text.replaceAll("\\s+", "");
    }
}
