package com.susukkang.fgc.compare.dto;

/**
 * 설명 : 챗봇 1단계에서 고르는 비교 카테고리와 비교 가능한 보험사 수
 */
public record CompareCategoryResponse(String code, String label, int insurerCount) {
}
