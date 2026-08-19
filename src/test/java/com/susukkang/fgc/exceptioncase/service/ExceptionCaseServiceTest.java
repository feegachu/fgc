package com.susukkang.fgc.exceptioncase.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.audit.mapper.AuditLogMapper;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseSearchDTO;
import com.susukkang.fgc.exceptioncase.mapper.ExceptionCaseActionMapper;
import com.susukkang.fgc.exceptioncase.mapper.ExceptionCaseQueryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        // count → search 순서로 정확히 1회 — 보정 전 offset(160)의 선행 조회가 없어야 한다
        InOrder inOrder = inOrder(queryMapper);
        inOrder.verify(queryMapper).count(any(), anyList());
        inOrder.verify(queryMapper).search(any(), anyList(), eq(40), eq(20));
        verify(queryMapper, times(1)).search(any(), anyList(), anyInt(), anyInt());
    }

    /** IF-API-43 페이징 경계: 마지막 페이지를 정확히 요청하면 보정 없이 그대로 조회한다. */
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

    /** IF-API-43: size 는 최대 100 — 상한값은 통과하고 초과는 COMMON_002 로 거부한다. */
    @Test
    void rejectsSizeOverMax() {
        given(queryMapper.count(any(), anyList())).willReturn(0L);
        given(queryMapper.countOpenByType()).willReturn(List.of());

        assertThat(service.search(new ExceptionCaseSearchDTO(), 1, 100).size()).isEqualTo(100);

        assertThatThrownBy(() -> service.search(new ExceptionCaseSearchDTO(), 1, 101))
                .isInstanceOf(FgcBusinessException.class)
                .satisfies(thrown -> assertThat(((FgcBusinessException) thrown).getErrorCode())
                        .isEqualTo(FgcErrorCode.COMMON_002));
    }
}
