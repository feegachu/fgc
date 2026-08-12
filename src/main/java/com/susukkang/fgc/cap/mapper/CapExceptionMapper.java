package com.susukkang.fgc.cap.mapper;

import com.susukkang.fgc.cap.dto.CapExceptionInsertDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 설명 : 1,200% 한도 계산 결과에 따른 예외 등록을 처리하는 Mapper 인터페이스
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
@Mapper
public interface CapExceptionMapper {

    /**
     * 설명 : 한도 주의 또는 위반 예외 건을 등록한다
     *
     * @param capExceptionInsertDTO 예외 등록 정보
     * @return 등록 또는 갱신된 행 수
     * @author hjKang
     * @since 2026-08-12
     */
    int insertException(CapExceptionInsertDTO capExceptionInsertDTO);

    /**
     * 설명 : 한도 룰셋 ID를 통해 정책 버전 ID를 조회한다
     *
     * @param capRuleSetId 한도 룰셋 ID
     * @return 정책 버전 ID
     * @author hjKang
     * @since 2026-08-12
     */
    Long selectPolicyVersionId(
            @Param("capRuleSetId") Long capRuleSetId
    );
}