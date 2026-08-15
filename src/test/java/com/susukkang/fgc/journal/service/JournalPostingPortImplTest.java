package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.journal.domain.ExpectedInsurerIncomeJournalCommand;
import com.susukkang.fgc.journal.dto.JournalHeaderDraft;
import com.susukkang.fgc.journal.dto.JournalHeaderRow;
import com.susukkang.fgc.journal.dto.ScheduleJournalSourceRow;
import com.susukkang.fgc.journal.dto.TransactionJournalSourceRow;
import com.susukkang.fgc.journal.mapper.JournalMapper;
import com.susukkang.fgc.journal.mapper.JournalPostingSourceMapper;
import com.susukkang.fgc.validation.batch.contract.JournalPostingResult;
import com.susukkang.fgc.validation.batch.contract.ValidationJobContext;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * #142 journalPostingStep(Step 6a) 구현체 단위테스트. 실제 DB 대신 Mapper·하위 Service를
 * mock으로 대체해 오케스트레이션(원천 조회 → draft → 저장 → 기표)과 06_배치_Step_협업계약.md:18
 * "기표 오류는 Step 실패" 원칙(개별 원천 실패를 catch하지 않고 그대로 전파)만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class JournalPostingPortImplTest {

    @Mock
    private JournalPostingSourceMapper sourceMapper;
    @Mock
    private JournalEntryDraftService draftService;
    @Mock
    private JournalPersistenceService persistenceService;
    @Mock
    private JournalMapper journalMapper;

    private JournalPostingPortImpl port() {
        return new JournalPostingPortImpl(sourceMapper, draftService, persistenceService, journalMapper);
    }

    private ValidationStepContext newContext(long validationRunId) {
        ValidationJobContext job = new ValidationJobContext(
                LocalDate.of(2026, 8, 1), 1L, ValidationRunType.MONTHLY, 3L, "req-142");
        return new ValidationStepContext(validationRunId, job);
    }

    private ScheduleJournalSourceRow scheduleRow(Long scheduleLineId) {
        ScheduleJournalSourceRow row = new ScheduleJournalSourceRow();
        row.setScheduleLineId(scheduleLineId);
        row.setContractId(10L);
        row.setPolicyVersionId(20L);
        row.setCommissionItemId(30L);
        row.setJournalDate(LocalDate.of(2026, 8, 15));
        row.setAmount(new BigDecimal("50000.00"));
        row.setDescription("test");
        return row;
    }

    private JournalHeaderRow draftHeaderRow(Long id) {
        JournalHeaderRow row = new JournalHeaderRow();
        row.setJournalHeaderId(id);
        row.setStatus("DRAFT");
        return row;
    }

    private JournalHeaderRow postedHeaderRow(Long id) {
        JournalHeaderRow row = new JournalHeaderRow();
        row.setJournalHeaderId(id);
        row.setStatus("POSTED");
        return row;
    }

    @Test
    // 4종 원천이 각각 1건씩 있으면 4건 모두 draft → 저장 → 기표까지 진행되고 postedJournalCount=4
    void postsAllFourSourceTypes() {
        given(sourceMapper.findExpectedInsurerIncomeSources(any(), any())).willReturn(List.of(scheduleRow(1L)));
        given(sourceMapper.findExpectedFcPayoutSources(any(), any())).willReturn(List.of(scheduleRow(2L)));
        given(sourceMapper.findActualInsurerStatementSources(any(), any())).willReturn(List.of(transactionRow(3L)));
        given(sourceMapper.findConfirmedFcPayoutSources(any(), any())).willReturn(List.of(transactionRow(4L)));

        given(draftService.draftExpectedInsurerIncome(any())).willReturn(mock(JournalHeaderDraft.class));
        given(draftService.draftExpectedFcPayout(any())).willReturn(mock(JournalHeaderDraft.class));
        given(draftService.draftActualInsurerStatement(any())).willReturn(mock(JournalHeaderDraft.class));
        given(draftService.draftConfirmedFcPayout(any())).willReturn(mock(JournalHeaderDraft.class));

        given(persistenceService.saveDraft(any(), any(), any()))
                .willReturn(draftHeaderRow(101L), draftHeaderRow(102L), draftHeaderRow(103L), draftHeaderRow(104L));

        JournalPostingResult result = port().post(newContext(42L));

        assertThat(result.postedJournalCount()).isEqualTo(4);
        assertThat(result.skippedCount()).isZero();
        assertThat(result.failureCount()).isZero();
        verify(journalMapper).markPosted(101L, 3L);
        verify(journalMapper).markPosted(102L, 3L);
        verify(journalMapper).markPosted(103L, 3L);
        verify(journalMapper).markPosted(104L, 3L);
    }

    @Test
    // 재실행 멱등성: saveDraft가 이미 POSTED인 기존 행을 그대로 돌려주면 markPosted를 다시 부르지 않는다
    void rerunSkipsMarkPostedWhenAlreadyPosted() {
        given(sourceMapper.findExpectedInsurerIncomeSources(any(), any())).willReturn(List.of(scheduleRow(1L)));
        given(sourceMapper.findExpectedFcPayoutSources(any(), any())).willReturn(List.of());
        given(sourceMapper.findActualInsurerStatementSources(any(), any())).willReturn(List.of());
        given(sourceMapper.findConfirmedFcPayoutSources(any(), any())).willReturn(List.of());

        given(draftService.draftExpectedInsurerIncome(any())).willReturn(mock(JournalHeaderDraft.class));
        given(persistenceService.saveDraft(any(), any(), any())).willReturn(postedHeaderRow(101L));

        JournalPostingResult result = port().post(newContext(42L));

        assertThat(result.postedJournalCount()).isEqualTo(1);
        verify(journalMapper, never()).markPosted(any(), any());
    }

    @Test
    // EXPECTED_INSURER_INCOME 원천 필드가 커맨드에 그대로 매핑되는지
    void mapsScheduleRowFieldsIntoCommand() {
        ScheduleJournalSourceRow row = scheduleRow(7L);
        given(sourceMapper.findExpectedInsurerIncomeSources(any(), any())).willReturn(List.of(row));
        given(sourceMapper.findExpectedFcPayoutSources(any(), any())).willReturn(List.of());
        given(sourceMapper.findActualInsurerStatementSources(any(), any())).willReturn(List.of());
        given(sourceMapper.findConfirmedFcPayoutSources(any(), any())).willReturn(List.of());
        given(draftService.draftExpectedInsurerIncome(any())).willReturn(mock(JournalHeaderDraft.class));
        given(persistenceService.saveDraft(any(), any(), any())).willReturn(draftHeaderRow(200L));

        port().post(newContext(99L));

        ArgumentCaptor<ExpectedInsurerIncomeJournalCommand> captor =
                ArgumentCaptor.forClass(ExpectedInsurerIncomeJournalCommand.class);
        verify(draftService).draftExpectedInsurerIncome(captor.capture());
        ExpectedInsurerIncomeJournalCommand command = captor.getValue();
        assertThat(command.getScheduleLineId()).isEqualTo(7L);
        assertThat(command.getContractId()).isEqualTo(row.getContractId());
        assertThat(command.getPolicyVersionId()).isEqualTo(row.getPolicyVersionId());
        assertThat(command.getValidationRunId()).isEqualTo(99L);
        assertThat(command.getCommissionItemId()).isEqualTo(row.getCommissionItemId());
        assertThat(command.getJournalDate()).isEqualTo(row.getJournalDate());
        assertThat(command.getExpectedAmount()).isEqualByComparingTo(row.getAmount());
    }

    @Test
    // 개별 원천 저장 실패는 catch하지 않고 그대로 전파해 Step을 실패시킨다(협업계약 원칙)
    void propagatesPersistenceFailureWithoutSwallowing() {
        given(sourceMapper.findExpectedInsurerIncomeSources(any(), any())).willReturn(List.of(scheduleRow(1L)));
        given(draftService.draftExpectedInsurerIncome(any())).willReturn(mock(JournalHeaderDraft.class));
        given(persistenceService.saveDraft(any(), any(), any())).willThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> port().post(newContext(42L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        verify(journalMapper, never()).markPosted(any(), any());
    }

    private TransactionJournalSourceRow transactionRow(Long commissionTransactionId) {
        TransactionJournalSourceRow row = new TransactionJournalSourceRow();
        row.setCommissionTransactionId(commissionTransactionId);
        row.setContractId(10L);
        row.setPolicyVersionId(20L);
        row.setCommissionItemId(30L);
        row.setJournalDate(LocalDate.of(2026, 8, 15));
        row.setAmount(new BigDecimal("50000.00"));
        row.setDescription("test");
        return row;
    }
}
