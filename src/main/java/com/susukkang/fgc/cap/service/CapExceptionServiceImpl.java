package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.cap.dto.CapCalculationResult;
import com.susukkang.fgc.cap.mapper.CapExceptionMapper;
import com.susukkang.fgc.common.code.CapResultStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 설명 : 한도 검증 결과가 주의 또는 위반인 경우 중복 없는 한도 예외 건을 생성하는 서비스 구현체
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
@Service
@RequiredArgsConstructor
public class CapExceptionServiceImpl implements CapExceptionService {
    private final CapExceptionMapper capExceptionMapper;
    /**
     * 설명 : 1200% 한도 계산 결과를 바탕으로 예외 건을 생성하는 함수
     *
     * @param  paymentId 지급 ID
     * @param  agentId 설계사 ID
     * @param  result 1200%한도 계산 결과
     * @author hjKang
     * @since 2026-08-12
     */
    @Override
    public void createIfNecessary(Long paymentId,Long agentId,CapCalculationResult result) {
        if (result == null)
            throw new IllegalArgumentException("1200% 한도 결과가 없습니다.");

        switch (result.resultStatus()){
            case CapResultStatus.WARNING ->{
                //WARNING 이므로 exception에 WARNING으로 표기;
                capExceptionMapper.insertException(paymentId,agentId,CapResultStatus.WARNING);
            }
            case CapResultStatus.VIOLATION ->{
                //1200% 한도를 넘었으므로 exception에 VIOLATION 으로 표기;
                capExceptionMapper.insertException(paymentId,agentId,CapResultStatus.WARNING);
            }
        }

    }
}