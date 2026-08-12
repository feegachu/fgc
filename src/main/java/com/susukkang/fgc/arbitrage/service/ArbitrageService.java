package com.susukkang.fgc.arbitrage.service;

import com.susukkang.fgc.arbitrage.dto.ArbitrageCheckSearchCondition;
import com.susukkang.fgc.arbitrage.dto.ArbitrageCheckSummary;
import com.susukkang.fgc.arbitrage.dto.ArbitrageCheckView;
import com.susukkang.fgc.arbitrage.dto.ArbitrageSearchResponse;
import com.susukkang.fgc.arbitrage.mapper.ArbitrageMapper;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 설명 : 차익거래 검증 결과 조회 업무를 처리한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
@Service
@RequiredArgsConstructor
public class ArbitrageService {
    private final ArbitrageMapper arbitrageMapper;

    /**
     * 설명 : 검색 조건에 해당하는 차익거래 검증 결과 요약과 페이징 목록을 조회한다.
     *
     * @param condition 검색 조건
     * @param page 페이지 번호
     * @param size 페이지 크기
     * @return 차익거래 검증 결과 요약 및 목록
     * @author hjKang
     * @since 2026-08-12
     */
    public ArbitrageSearchResponse selectByCondition(
            ArbitrageCheckSearchCondition condition, int page, int size) {
        // 페이지 번호 유효성 검증
        if (page < 1)
            throw validationException("page", "page는 1 이상이어야 합니다.");

        // 페이지 크기 유효성 검증
        if (size < 1 || size > 100)
            throw validationException("size", "size는 1 이상 100 이하여야 합니다.");

        // 조회 시작 위치 계산 및 정수 범위 검증
        long offsetLong = (long) (page - 1) * size;
        if (offsetLong > Integer.MAX_VALUE)
            throw validationException("page", "요청할 수 있는 페이지 범위를 초과했습니다.");
        int offset = (int) offsetLong;

        // 검색 조건에 해당하는 차익거래 검증 결과 목록 조회
        List<ArbitrageCheckView> arbitrageCheckList =
                arbitrageMapper.selectByCondition(condition, offset, size);
        // 검색 조건에 해당하는 판정별 요약 건수 조회
        ArbitrageCheckSummary summary = arbitrageMapper.arbitrageCheckSummary(condition);

        if (summary == null)
            summary = new ArbitrageCheckSummary();

        // 조회 목록을 페이지 응답으로 변환
        PageResponse<ArbitrageCheckView> items = PageResponse.of(
                arbitrageCheckList,
                page,
                size,
                summary.getTotalArbitrageChecks(),
                "arbitrageCheckId,desc"
        );

        // 요약 정보와 페이징 목록을 하나의 응답으로 반환
        return ArbitrageSearchResponse.builder()
                .summary(summary)
                .items(items)
                .build();
    }

    private FgcBusinessException validationException(String field, String detail) {
        return new FgcBusinessException(
                FgcErrorCode.COMMON_002,
                field,
                Map.of("field", field),
                detail
        );
    }
}
