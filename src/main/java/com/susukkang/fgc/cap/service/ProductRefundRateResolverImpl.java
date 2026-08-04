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

    /** 1,200% 한도 판정에 쓰는 차월 (REG-08) */
    private static final int LIMIT_ADDITION_MONTH = 12;

    private final RefundRateMapper refundRateMapper;

    @Override
    public Optional<RefundRateResolution> resolve(RefundRateQuery query) {
        // 1) (보험사·상품·납입기간·채널) 조합으로 기준일에 유효한 표 헤더를 먼저 찾는다.
        //    이 조합에 해당하는 표가 아예 없으면(예: 이 납입기간용 표를 아직 안 만든 상품) 빈 값을 돌려주고,
        //    CapCalculator 가 이를 REVIEW_REQUIRED 로 처리한다 — 값을 추정해서 채우지 않는다(REG-23).
        RefundRateTableView table = refundRateMapper.findApplicableTable(
                query.insurerId(), query.productId(), query.paymentTermMonths(),
                query.channelCode(), query.asOfDate());
        if (table == null) {
            return Optional.empty();
        }

        // 2) 1,200% 한도 가산에는 표 전체가 아니라 12차월 값 딱 한 줄만 쓴다(REG-08).
        //    표는 1~36차월까지 있지만(차익거래 판정용), 여기서는 그중 12차월만 조회한다.
        BigDecimal month12Rate = refundRateMapper.findRateAtMonth(table.getRefundRateTableId(), LIMIT_ADDITION_MONTH);
        if (month12Rate == null) {
            return Optional.empty();
        }

        // policyVersionId/versionNo 를 같이 돌려주는 이유: cap_check 에 "어느 버전의 표로 판정했는지"를
        // 남겨야 나중에 표가 개정돼도 과거 판정 근거를 그대로 재현할 수 있다.
        return Optional.of(new RefundRateResolution(
                table.getRefundRateTableId(),
                table.getPolicyVersionId(),
                table.getVersionNo(),
                month12Rate
        ));
    }
}
