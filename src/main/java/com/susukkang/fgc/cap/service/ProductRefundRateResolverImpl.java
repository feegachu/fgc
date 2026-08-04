package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.cap.dto.RefundRateQuery;
import com.susukkang.fgc.cap.dto.RefundRateResolution;
import com.susukkang.fgc.cap.dto.RefundRateTableView;
import com.susukkang.fgc.cap.mapper.RefundRateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ProductRefundRateResolverImpl implements ProductRefundRateResolver {

    // 1,200% 한도 판정에 쓰는 차월 (REG-08)
    private static final int LIMIT_ADDITION_MONTH = 12;

    private final RefundRateMapper refundRateMapper;

    @Override
    public Optional<RefundRateResolution> resolve(RefundRateQuery query) {
        // 1) (보험사·상품·납입기간·채널) 조합으로 기준일에 유효한 표 헤더를 먼저 찾음
        RefundRateTableView table = refundRateMapper.findApplicableTable(
                query.insurerId(), query.productId(), query.paymentTermMonths(),
                query.channelCode(), query.asOfDate());
        // 조합에 해당하는 표가 아예 없으면 빈 값을 돌려주고, CapCalculator가 REVIEW_REQUIRED로 처리
        if (table == null) {
            return Optional.empty();
        }

        // 2) 1,200% 한도 가산에는 표 전체가 아니라 12차월 값 딱 한 줄만 씀 (REG-08)
        BigDecimal month12Rate = refundRateMapper.findRateAtMonth(table.getRefundRateTableId(), LIMIT_ADDITION_MONTH);
        if (month12Rate == null) {
            return Optional.empty();
        }

        // policyVersionId/versionNo를 같이 돌려주는 이유: cap_check에 과거 판정 근거 기록
        return Optional.of(new RefundRateResolution(
                table.getRefundRateTableId(),
                table.getPolicyVersionId(),
                table.getVersionNo(),
                month12Rate
        ));
    }
}
