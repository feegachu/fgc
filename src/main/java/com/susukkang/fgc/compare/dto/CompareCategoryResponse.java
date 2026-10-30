package com.susukkang.fgc.compare.dto;

import java.util.List;

/**
 * 설명 : 챗봇 1단계에서 고르는 비교 카테고리와 비교 가능한 보험사. insurers 는 세부 조건의 보험사 선택지다.
 */
public record CompareCategoryResponse(String code, String label, int insurerCount, List<InsurerOption> insurers) {

    public record InsurerOption(long id, String name) {
    }
}
