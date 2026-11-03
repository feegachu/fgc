package com.susukkang.fgc.news.controller;

import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.news.dto.NewsHeadline;
import com.susukkang.fgc.news.service.NewsHeadlineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "보험 뉴스", description = "설계사 홈 챗봇에 보여 주는 최신 뉴스 헤드라인")
@RestController
@RequestMapping("/api/v1/news")
@RequiredArgsConstructor
public class NewsController {

    private final NewsHeadlineService newsHeadlineService;

    @Operation(summary = "최신 뉴스 헤드라인", description = "보험 전문 매체 RSS 의 최신 제목과 원문 링크만 돌려준다. 가져오지 못하면 빈 목록이다.")
    @GetMapping("/headlines")
    @PreAuthorize("hasRole('AGENT')")
    public ApiResponse<List<NewsHeadline>> headlines() {
        return ApiResponse.success(newsHeadlineService.latest());
    }
}
