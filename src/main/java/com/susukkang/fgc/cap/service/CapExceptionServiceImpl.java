package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.dto.CapCalculationResult;

/**
 * 설명 : 한도 초과 예외 처리 기능을 구현하는 서비스 클래스
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
public class CapExceptionServiceImpl implements CapExceptionService{
    /**
     * 설명 : 수수료 한도 초과 또는 한도가 90%에 가까워진 건을 예외 목록에 자동으로 처리한다.
     *
     * @param  command 수수료 계산 명령 객체
     * @param  result 수수료 계산 결과 객체
     * @author hjKang
     * @since 2026-08-12
     */
    @Override
    public void createIfNecessary(CapCalculationCommand command, CapCalculationResult result) {

    }
}