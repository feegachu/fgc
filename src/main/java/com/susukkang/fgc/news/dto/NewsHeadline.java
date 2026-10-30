package com.susukkang.fgc.news.dto;

/** 뉴스 헤드라인. 제목과 원문 링크만 담고 본문·요약은 가져오지 않는다. */
public record NewsHeadline(String title, String link, String publishedAt) {
}
