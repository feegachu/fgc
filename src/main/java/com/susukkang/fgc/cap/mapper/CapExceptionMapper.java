package com.susukkang.fgc.cap.mapper;

import com.susukkang.fgc.cap.dto.CapExceptionInsertDTO;
import com.susukkang.fgc.cap.dto.CapExceptionResolveCommand;
import com.susukkang.fgc.cap.dto.CapExceptionStatusRow;
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

    /** 설명 : 해결할 한도 예외의 현재 상태를 잠금 조회한다. */
    CapExceptionStatusRow selectExceptionForUpdate(
            @Param("exceptionCaseId") Long exceptionCaseId
    );

    /** 설명 : 한도 예외 해결조치 이력을 저장한다. */
    int insertExceptionAction(CapExceptionResolveCommand command);

    /** 설명 : 한도 예외를 해결 완료 상태로 변경한다. */
    int updateExceptionResolved(
            @Param("exceptionCaseId") Long exceptionCaseId
    );

    /** 설명 : 지급 건의 미해결 한도 위반 예외 존재 여부를 조회한다. */
    boolean existsUnresolvedViolation(
            @Param("paymentId") Long paymentId
    );
}
