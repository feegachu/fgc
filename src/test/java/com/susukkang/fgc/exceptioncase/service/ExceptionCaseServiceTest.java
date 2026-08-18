package com.susukkang.fgc.exceptioncase.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.audit.mapper.AuditLogMapper;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseSearchDTO;
import com.susukkang.fgc.exceptioncase.mapper.ExceptionCaseActionMapper;
import com.susukkang.fgc.exceptioncase.mapper.ExceptionCaseQueryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * FGC-FUN-052·IF-API-43 예외함 페이징: 범위 밖 page 는 count 를 먼저 세어 보정한다 —
 * 대량 OFFSET 행 조회가 실행되지 않고, 응답 page 가 보정된 값을 담는다.
 */
@ExtendWith(MockitoExtension.class)
class ExceptionCaseServiceTest {

    @Mock
    private ExceptionCaseQueryMapper queryMapper;

    @Mock
    private ExceptionCaseActionMapper actionMapper;

    @Mock
    private AuditLogMapper auditLogMapper;

    private ExceptionCaseService service;

    @BeforeEach
    void setUp() {
        service = new ExceptionCaseService(queryMapper, actionMapper, auditLogMapper, new ObjectMapper());
    }

    @Test
    void clampsOutOfRangePageBeforeFetchingRows() {
        given(queryMapper.count(any(), anyList())).willReturn(45L);
        given(queryMapper.search(any(), anyList(), anyInt(), anyInt())).willReturn(List.of());
        given(queryMapper.countOpenByType()).willReturn(List.of());

        var result = service.search(new ExceptionCaseSearchDTO(), 9, 20);

        assertThat(result.page()).isEqualTo(3);
        verify(queryMapper).search(any(), anyList(), eq(40), eq(20));
    }

    /** 마지막 페이지를 정확히 요청하면 보정 없이 그대로 조회한다 (경계값). */
    @Test
    void keepsPageWhenExactlyOnLastPage() {
        given(queryMapper.count(any(), anyList())).willReturn(45L);
        given(queryMapper.search(any(), anyList(), anyInt(), anyInt())).willReturn(List.of());
        given(queryMapper.countOpenByType()).willReturn(List.of());

        var result = service.search(new ExceptionCaseSearchDTO(), 3, 20);

        assertThat(result.page()).isEqualTo(3);
        verify(queryMapper).search(any(), anyList(), eq(40), eq(20));
    }

    @Test
    void skipsRowQueryWhenTotalIsZero() {
        given(queryMapper.count(any(), anyList())).willReturn(0L);
        given(queryMapper.countOpenByType()).willReturn(List.of());

        var result = service.search(new ExceptionCaseSearchDTO(), 5, 20);

        assertThat(result.content()).isEmpty();
        assertThat(result.page()).isEqualTo(1);
        verify(queryMapper, never()).search(any(), anyList(), anyInt(), anyInt());
    }

    /** IF-API-43: size 는 최대 100 — 초과 요청은 COMMON_002 로 거부한다. */
    @Test
    void rejectsSizeOverMax() {
        assertThatThrownBy(() -> service.search(new ExceptionCaseSearchDTO(), 1, 101))
                .isInstanceOf(FgcBusinessException.class);
    }
}
