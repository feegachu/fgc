package com.susukkang.fgc.cap.mapper;

import com.susukkang.fgc.common.code.CapResultStatus;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.data.repository.query.Param;

/**
 * 설명 : 1200% 한도 계산 결과를 바탕으로 예외 건을 생성하는 Mapper interface
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
@Mapper
public interface CapExceptionMapper {
    // 1200% 한도 계산 결과를 바탕으로 예외 건을 생성
    void insertException(
            @Param("paymentId") Long paymentId,
            @Param("agentId") Long agentId,
            @Param("capResultStatus") CapResultStatus capResultStatus);
}
