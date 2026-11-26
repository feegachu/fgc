package com.susukkang.fgc.exceptioncase.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.exceptioncase.repository.ExceptionActionRepository;
import com.susukkang.fgc.audit.repository.AuditLogRepository;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.code.ExceptionActionType;
import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.exceptioncase.dto.ExceptionActionRequest;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseActionTarget;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseSearchDTO;
import com.susukkang.fgc.exceptioncase.event.ExceptionCaseClosed;
import com.susukkang.fgc.exceptioncase.repository.ExceptionCaseRepository;
import com.susukkang.fgc.exceptioncase.repository.ExceptionCaseQueryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

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
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * FGC-FUN-052·IF-API-43 예외함 페이징: 범위 밖 page 는 count 를 먼저 세어 보정한다 —
 * 대량 OFFSET 행 조회가 실행되지 않고, 응답 page 가 보정된 값을 담는다.
 */
@ExtendWith(MockitoExtension.class)
class ExceptionCaseServiceTest {

    @Mock
    private ExceptionCaseQueryRepository queryRepository;

    @Mock
    private ExceptionCaseRepository caseRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private ExceptionActionRepository actionRepository;

    @Mock
    private ApplicationEventPublisher events;

    private ExceptionCaseService service;

    @BeforeEach
    void setUp() {
        service = new ExceptionCaseService(queryRepository, caseRepository, actionRepository, auditLogRepository,
                new ObjectMapper(), events);
    }

    @Test
    void clampsOutOfRangePageBeforeFetchingRows() {
        given(queryRepository.count(any(), anyList())).willReturn(45L);
        given(queryRepository.search(any(), anyList(), anyInt(), anyInt())).willReturn(List.of());
        given(queryRepository.countOpenByType()).willReturn(List.of());

        var result = service.search(new ExceptionCaseSearchDTO(), 9, 20);

        assertThat(result.page()).isEqualTo(3);
        // count → search 순서로 정확히 1회 — 보정 전 offset(160)의 선행 조회가 없어야 한다
        InOrder inOrder = inOrder(queryRepository);
        inOrder.verify(queryRepository).count(any(), anyList());
        inOrder.verify(queryRepository).search(any(), anyList(), eq(40), eq(20));
        verify(queryRepository, times(1)).search(any(), anyList(), anyInt(), anyInt());
    }

    /** IF-API-43 페이징 경계: 마지막 페이지를 정확히 요청하면 보정 없이 그대로 조회한다. */
    @Test
    void keepsPageWhenExactlyOnLastPage() {
        given(queryRepository.count(any(), anyList())).willReturn(45L);
        given(queryRepository.search(any(), anyList(), anyInt(), anyInt())).willReturn(List.of());
        given(queryRepository.countOpenByType()).willReturn(List.of());

        var result = service.search(new ExceptionCaseSearchDTO(), 3, 20);

        assertThat(result.page()).isEqualTo(3);
        verify(queryRepository).search(any(), anyList(), eq(40), eq(20));
    }

    /** IF-API-43·FGC-FUN-052: 0건이면 행 조회를 생략하고 page 는 1로 되돌린다. */
    @Test
    void skipsRowQueryWhenTotalIsZero() {
        given(queryRepository.count(any(), anyList())).willReturn(0L);
        given(queryRepository.countOpenByType()).willReturn(List.of());

        var result = service.search(new ExceptionCaseSearchDTO(), 5, 20);

        assertThat(result.content()).isEmpty();
        assertThat(result.page()).isEqualTo(1);
        verify(queryRepository, never()).search(any(), anyList(), anyInt(), anyInt());
        verify(queryRepository, never()).findActionsByCaseIds(anyList());
        verify(queryRepository, never()).findOccurrencesByCaseIds(anyList());
    }

    @ParameterizedTest
    @CsvSource({"0,20", "-1,20", "1,0", "1,-1", "1,101"})
    void rejectsInvalidPagingBeforeQuery(int page, int size) {
        assertThatThrownBy(() -> service.search(new ExceptionCaseSearchDTO(), page, size))
                .isInstanceOf(FgcBusinessException.class);
        verifyNoInteractions(queryRepository);
    }

    @Test
    void rejectsInvalidStatusBeforeQuery() {
        ExceptionCaseSearchDTO criteria = new ExceptionCaseSearchDTO();
        criteria.setStatus("INVALID");
        assertThatThrownBy(() -> service.search(criteria, 1, 20))
                .isInstanceOf(FgcBusinessException.class);
        verifyNoInteractions(queryRepository);
    }

    @Test
    void rejectsOffsetOverflowAfterCountingBeforeFetchingRows() {
        given(queryRepository.count(any(), anyList())).willReturn(3_000_000_000L);
        assertThatThrownBy(() -> service.search(new ExceptionCaseSearchDTO(), 30_000_000, 100))
                .isInstanceOf(FgcBusinessException.class)
                .satisfies(thrown -> assertThat(((FgcBusinessException) thrown).getErrorCode())
                        .isEqualTo(FgcErrorCode.COMMON_002));
        verify(queryRepository, never()).search(any(), anyList(), anyInt(), anyInt());
        verify(queryRepository, never()).findActionsByCaseIds(anyList());
        verify(queryRepository, never()).findOccurrencesByCaseIds(anyList());
    }

    /** IF-API-43: size 는 최대 100 — 상한값은 통과하고 초과는 COMMON_002 로 거부한다. */
    @Test
    void rejectsSizeOverMax() {
        given(queryRepository.count(any(), anyList())).willReturn(0L);
        given(queryRepository.countOpenByType()).willReturn(List.of());

        assertThat(service.search(new ExceptionCaseSearchDTO(), 1, 100).size()).isEqualTo(100);

        assertThatThrownBy(() -> service.search(new ExceptionCaseSearchDTO(), 1, 101))
                .isInstanceOf(FgcBusinessException.class)
                .satisfies(thrown -> assertThat(((FgcBusinessException) thrown).getErrorCode())
                        .isEqualTo(FgcErrorCode.COMMON_002));
    }

    // FGC-FUN-089: 마감 알림이 확정 조건을 바로 다시 보도록 예외 종결을 알린다(IF-EVT-08).
    @Test
    void announcesAClosedException() {
        given(caseRepository.findByIdForUpdate(11L)).willReturn(
                new ExceptionCaseActionTarget(11L, "RECONCILIATION_MISMATCH", ExceptionStatus.IN_REVIEW, 7L));
        given(actionRepository.findNextActionSeq(11L)).willReturn(2);
        given(caseRepository.updateCaseAfterAction(eq(11L), eq(ExceptionStatus.RESOLVED), eq(7L), any())).willReturn(1);

        service.action(11L, new ExceptionActionRequest(ExceptionActionType.RESOLVE, "원천 수정 확인", null), 7L, "settle01");

        verify(events).publishEvent(new ExceptionCaseClosed(11L));
    }

    // FGC-FUN-089: 종결하지 않는 조치는 알리지 않는다.
    @Test
    void doesNotAnnounceAnExceptionThatStaysOpen() {
        given(caseRepository.findByIdForUpdate(11L)).willReturn(
                new ExceptionCaseActionTarget(11L, "RECONCILIATION_MISMATCH", ExceptionStatus.NEW, null));
        given(actionRepository.findNextActionSeq(11L)).willReturn(1);
        given(caseRepository.updateCaseAfterAction(11L, ExceptionStatus.IN_REVIEW, 7L, null)).willReturn(1);

        service.action(11L, new ExceptionActionRequest(ExceptionActionType.START_REVIEW, "검토 시작", null), 7L, "settle01");

        // publishEvent 는 Object 와 ApplicationEvent 두 가지라 any() 로는 실제로 쓰는 쪽을 보지 못한다. 호출 자체가 없어야 한다.
        verifyNoInteractions(events);
    }

    @Test
    void assignsSelectedReviewerAndKeepsActorInHistory() {
        given(caseRepository.findByIdForUpdate(11L)).willReturn(
                new ExceptionCaseActionTarget(11L, "CAP_VIOLATION", ExceptionStatus.NEW, null));
        given(queryRepository.isAssignableUser(8L)).willReturn(true);
        given(actionRepository.findNextActionSeq(11L)).willReturn(1);
        given(caseRepository.updateCaseAfterAction(11L, ExceptionStatus.IN_REVIEW, 8L, null)).willReturn(1);

        var response = service.action(11L,
                new ExceptionActionRequest(ExceptionActionType.ASSIGN, "담당자에게 전달", null, 8L),
                7L, "settle01");

        assertThat(response.toStatus()).isEqualTo(ExceptionStatus.IN_REVIEW);
        assertThat(response.actionBy()).isEqualTo(7L);
        verify(caseRepository).updateCaseAfterAction(11L, ExceptionStatus.IN_REVIEW, 8L, null);
        var audit = org.mockito.ArgumentCaptor.forClass(com.susukkang.fgc.audit.entity.AuditLog.class);
        verify(auditLogRepository).saveAndFlush(audit.capture());
        assertThat(audit.getValue()).extracting("afterValue").asString().contains("\"assignedTo\":8");
    }

    @Test
    void rejectsUnavailableAssigneeBeforeWritingHistory() {
        given(caseRepository.findByIdForUpdate(11L)).willReturn(
                new ExceptionCaseActionTarget(11L, "CAP_VIOLATION", ExceptionStatus.NEW, null));
        given(queryRepository.isAssignableUser(8L)).willReturn(false);

        assertThatThrownBy(() -> service.action(11L,
                new ExceptionActionRequest(ExceptionActionType.ASSIGN, "담당자에게 전달", null, 8L),
                7L, "settle01"))
                .isInstanceOf(FgcBusinessException.class)
                .satisfies(thrown -> assertThat(((FgcBusinessException) thrown).getErrorCode())
                        .isEqualTo(FgcErrorCode.COMMON_002));
        verifyNoInteractions(actionRepository, auditLogRepository);
        verify(caseRepository, never()).updateCaseAfterAction(any(), any(), any(), any());
    }
}
