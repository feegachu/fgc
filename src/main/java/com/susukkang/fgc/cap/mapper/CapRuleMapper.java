package com.susukkang.fgc.cap.mapper;

import com.susukkang.fgc.cap.dto.CapRuleItemView;
import com.susukkang.fgc.cap.dto.CapRuleSetView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface CapRuleMapper {

    /**
     * 지급단계·계약일 범위 안에서 ACTIVE 정책의 cap_rule_set을 조회
     * insurer_id/product_group_code/channel_code가 NULL인 행은 "전체 적용" 와일드카드이고,
     * 값이 채워진 행이 더 구체적인 예외 규칙이므로 구체적인 조건을 우선함
     * 후보가 여러 건이면 가장 일치하는 스코프 컬럼이 많은 1건만 돌려줌
     */
    CapRuleSetView findApplicableRuleSet(@Param("paymentStage") String paymentStage,
                                          @Param("contractDate") LocalDate contractDate,
                                          @Param("insurerId") Long insurerId,
                                          @Param("productGroupCode") String productGroupCode,
                                          @Param("channelCode") String channelCode);

    /** 룰셋에 속한 수수료 항목별 산입·제외·검토필요 분류 전체 */
    List<CapRuleItemView> findRuleItems(@Param("capRuleSetId") Long capRuleSetId);
}
