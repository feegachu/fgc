package com.susukkang.fgc.transaction.service;

import com.susukkang.fgc.transaction.domain.CapCheckCommand;
import com.susukkang.fgc.transaction.domain.ExceptionCaseCommand;
import com.susukkang.fgc.transaction.mapper.CommissionPaymentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 설명 : 지급 건 검증 실패 이력을 독립 트랜잭션으로 보존하는 서비스
 *
 * @author yslee
 * @since 2026-08-06
 * @version 1.2
 */
@Service
@RequiredArgsConstructor
public class CommissionPaymentExceptionService {

    private final CommissionPaymentMapper mapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(ExceptionCaseCommand command) {
        mapper.insertExceptionCase(command);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveRejectedCapCheck(
            CapCheckCommand check,
            ExceptionCaseCommand exception
    ) {
        mapper.insertCapCheck(check);
        mapper.insertCapCheckDetail(check);
        mapper.insertExceptionCase(exception);
    }
}
