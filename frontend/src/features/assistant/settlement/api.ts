import { apiClient } from '../../../lib/api/client'
import { getContract } from '../../contract/api'
import type { ContractSchedule } from '../../contract/api'
import type { ScheduleDetail } from '../../schedule/api'
import { readPayment } from '../../transaction/form-api'
import { getPaymentGuideObserver } from '../../transaction/payment-guide'
import type { ValidationRunDetail, FinalizeChecklist } from '../../validation/api'
import type { History, Results } from '../../reconciliation/api'
import type { ExceptionSearchResult } from '../../exception/api'
import type { Evidence, Selection } from './state'

export async function guideGet<T>(path: string, signal: AbortSignal): Promise<T> {
  const { data } = await apiClient.request<T>(`/api/v1${path}`, { signal })
  signal.throwIfAborted()
  if (data == null) throw new Error('조회 결과가 없습니다. 다시 확인하세요.')
  return data
}
/** Read-only. No calculation, progress storage or business writes. */
export async function readEvidence(selection: Selection, signal: AbortSignal): Promise<Evidence> {
  const state: Evidence = { scheduleAcknowledged: false }
  if (selection.contractId) {
    state.contract = await getContract(selection.contractId, signal)
    const schedules = await guideGet<ContractSchedule>(
      `/contracts/${selection.contractId}/schedules?paymentStage=${selection.stage}`,
      signal,
    )
    const header = selection.scheduleId
      ? schedules.headers?.find((item) => item.scheduleHeaderId === selection.scheduleId)
      : schedules.headers?.find(
          (item) => item.activeYn && item.scheduleRegime === 'CURRENT' && item.schedulePurpose === 'OPERATIONAL',
        )
    if (selection.scheduleId && !header) throw new Error('선택한 스케줄이 계약·지급단계에 속하지 않습니다.')
    if (header?.scheduleHeaderId)
      state.schedule = await guideGet<ScheduleDetail>(`/schedules/${header.scheduleHeaderId}`, signal)
  }
  if (selection.paymentId) {
    state.payment = await readPayment(String(selection.paymentId), signal)
    const observer = getPaymentGuideObserver()
    if (observer) {
      const context = observer.readContext()
      if (context.entityRefs.some((ref) => ref.type === 'PAYMENT' && ref.id === selection.paymentId))
        state.paymentGuide = await observer.readBusinessState(context, signal)
    }
  }
  if (selection.validationRunId) {
    state.validation = await guideGet<ValidationRunDetail>(`/validation-runs/${selection.validationRunId}`, signal)
    state.checklist = await guideGet<FinalizeChecklist>(
      `/validation-runs/${selection.validationRunId}/finalize-checklist`,
      signal,
    )
    const exceptions = await guideGet<ExceptionSearchResult>(
      `/exceptions?validationMonth=${selection.month}-01&validationRunId=${selection.validationRunId}&status=OPEN&page=1&size=20`,
      signal,
    )
    state.openExceptions = exceptions.totalElements
    if (selection.exceptionId) {
      for (let page = 1; ; page++) {
        const list = await guideGet<ExceptionSearchResult>(
          `/exceptions?validationMonth=${selection.month}-01&validationRunId=${selection.validationRunId}&status=&page=${page}&size=20`,
          signal,
        )
        if (list.content?.some((item) => item.exceptionCaseId === selection.exceptionId)) break
        if (list.totalPages == null || page >= list.totalPages)
          throw new Error('선택한 예외가 현재 검증 실행에 속하지 않습니다.')
      }
    }
  }
  if (selection.reconciliationRunId) {
    // No single-run endpoint: traverse the existing history with its actual pagination.
    for (let page = 1; ; page++) {
      const history = await guideGet<History>(
        `/reconciliations?month=${selection.month}&stage=${selection.stage}&page=${page}&size=20`,
        signal,
      )
      const run = history.content?.find((item) => item.reconciliationRunId === selection.reconciliationRunId)
      if (run) {
        state.reconciliation = run
        break
      }
      if (history.totalPages == null || page >= history.totalPages)
        throw new Error('선택한 대사 실행을 현재 월·지급단계에서 찾을 수 없습니다.')
    }
    state.results = await guideGet<Results>(
      `/reconciliations/${selection.reconciliationRunId}/results?page=1&size=20`,
      signal,
    )
  }
  return state
}
