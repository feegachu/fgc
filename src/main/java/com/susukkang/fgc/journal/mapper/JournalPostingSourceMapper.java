package com.susukkang.fgc.journal.mapper;

import com.susukkang.fgc.journal.dto.ScheduleJournalSourceRow;
import com.susukkang.fgc.journal.dto.TransactionJournalSourceRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * journalPostingStep(Step 6a)이 원천(schedule_line, commission_transaction)에서 이번 검증
 * 실행 범위의 분개 대상을 읽는 전용 Mapper. 범위는 validation_target(selection_status='SELECTED')
 * 계약으로 좁히고, 그 안에서 다시 검증월로 좁힌다 — commission_transaction에는
 * validation_run_id 연결이 없어 화면정의서 확정조건(FGC_화면정의서_v2_0.md:1461,1466)과
 * 같은 방식(settlement_month = 검증월)으로 범위를 맞춘다. schedule_line도 같은 원칙을
 * 적용해 due_month = 검증월로 좁힌다(운영정책서 제38조는 원천유형만 정의하고 월 범위는
 * 명시하지 않아, 월 통합검증이라는 배치 성격상 이 기준을 택함 — 코드리뷰에서 재검토 가능).
 */
@Mapper
public interface JournalPostingSourceMapper {

    /** EXPECTED_INSURER_INCOME 원천: 원수사→GA 방향 스케줄 회차. */
    List<ScheduleJournalSourceRow> findExpectedInsurerIncomeSources(
            @Param("validationRunId") Long validationRunId,
            @Param("validationMonth") LocalDate validationMonth);

    /** EXPECTED_FC_PAYOUT 원천: GA→설계사 방향 스케줄 회차(수취 설계사가 정해진 것만). */
    List<ScheduleJournalSourceRow> findExpectedFcPayoutSources(
            @Param("validationRunId") Long validationRunId,
            @Param("validationMonth") LocalDate validationMonth);

    /** ACTUAL_INSURER_STATEMENT 원천: 원수사 실제 명세(단일 계약 귀속 건만). */
    List<TransactionJournalSourceRow> findActualInsurerStatementSources(
            @Param("validationRunId") Long validationRunId,
            @Param("validationMonth") LocalDate validationMonth);

    /** CONFIRMED_FC_PAYOUT 원천: GA 확정 지급 건(단일 계약 귀속 건만). */
    List<TransactionJournalSourceRow> findConfirmedFcPayoutSources(
            @Param("validationRunId") Long validationRunId,
            @Param("validationMonth") LocalDate validationMonth);
}
