import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router'
import { Button } from '../../components/Button'
import { Field } from '../../components/Field'
import { apiClient } from '../../lib/api/client'
import { errorText } from '../../lib/format'
import { useUnsavedWork } from '../../stores/unsavedWork'
import { toast } from '../../stores/toasts'
import { useAuth } from '../auth/useAuth'
import { applyActionToCache, postAction } from './api'
import type { ExceptionAction, ExceptionCase } from './api'

interface Context { guidance?: string; offeringMismatch?: boolean; rejectionRequiresEvidence?: boolean; administratorReviewRequired?: boolean; recheckAvailable?: boolean; links?: { label: string; href: string }[] }
export function ReviewPanel({ item, onClosed }: { item: ExceptionCase; onClosed?: () => void }) {
  const { user } = useAuth()
  const client = useQueryClient()
  const [reason, setReason] = useState('')
  const [evidence, setEvidence] = useState('')
  const [error, setError] = useState('')
  const [uncertain, setUncertain] = useState(false)
  const clearDirty = useUnsavedWork(Boolean(reason || evidence), JSON.stringify([reason, evidence]), `exception:${item.exceptionCaseId}:review:`)
  const path = `/api/v1/exceptions/${item.exceptionCaseId}/review`
  const context = useQuery({ queryKey: ['exceptions', 'review', item.exceptionCaseId], queryFn: async ({ signal }) => {
    const { data } = await apiClient.request<Context>(path, { signal })
    if (!data) throw new Error('검토 근거를 불러오지 못했습니다.')
    return data
  }, meta: { errorToast: false } })
  const mutation = useMutation({
    mutationKey: ['exception', 'write', item.exceptionCaseId],
    mutationFn: async (actionType: string) => {
      const action = await postAction(item.exceptionCaseId!, { actionType, reason, evidenceRef: evidence })
      if (!action.actionSeq || !action.actionType || !action.toStatus) { setUncertain(true); throw new Error('처리 응답을 확인하지 못했습니다. 다시 저장하지 말고 목록을 새로 조회해 이력을 확인하세요.') }
      return action
    },
    meta: { errorToast: false },
    onSuccess: action => {
      applyActionToCache(client, item.exceptionCaseId!, action)
      setReason(''); setEvidence(''); setError(''); clearDirty()
      toast(action.actionType === 'COMMENT' ? '검토 의견을 기록했습니다. 검토중 상태를 유지합니다.' : '판단 근거를 기록하고 반려로 종결했습니다.', 'success')
      if (action.actionType === 'REJECT') onClosed?.()
    },
    onError: error => setError(errorText(error, '검토 내용을 저장하지 못했습니다.')),
  })
  const recheck = useMutation({
    mutationKey: ['exception', 'write', item.exceptionCaseId],
    mutationFn: async () => {
      const { data } = await apiClient.request<{ resolved: boolean; message: string; action?: ExceptionAction }>(path + '/recheck', { method: 'POST' })
      if (!data) throw new Error('재검증 결과를 확인하지 못했습니다.')
      return data
    }, meta: { errorToast: false },
    onSuccess: result => {
      if (result.action) applyActionToCache(client, item.exceptionCaseId!, result.action)
      void client.invalidateQueries({ queryKey: ['arbitrage-checks'], refetchType: 'none' })
      void client.invalidateQueries({ queryKey: ['cap-checks'], refetchType: 'none' })
      toast(result.message, result.resolved ? 'success' : 'warning')
      if (result.resolved) onClosed?.()
    }, onError: error => setError(errorText(error, '원천을 재검증하지 못했습니다.')),
  })
  const busy = mutation.isPending || recheck.isPending || uncertain
  const administrator = ['GA_ADMIN', 'SYSTEM_ADMIN'].includes(user?.roleCode ?? '')
  const rejectionDisabled = busy || !reason.trim() || !context.isSuccess
    || Boolean(context.data?.rejectionRequiresEvidence && !evidence.trim())
    || Boolean(context.data?.administratorReviewRequired && !administrator)
  return <section data-guide-feature="features.exception.review-panel" className="surface">
    <div className="surface-header"><span className="surface-title">검토 기록 · 종결</span></div>
    <div className="surface-body exception-review-content">
      {context.data?.guidance && <p>{context.data.guidance}</p>}
      {context.error && <p role="alert" className="field-error">{errorText(context.error)}</p>}
      <div className="exception-review-links">{context.data?.links?.map(link => <Link data-guide-feature="features.exception.review.link" key={link.label} to={link.href}>{link.label}</Link>)}</div>
      <Field label="검토 메모 · 종결 근거" required><textarea data-guide-feature="features.exception.review.reason" value={reason} maxLength={1000} disabled={busy}
        placeholder="확인한 사실, 보험사 문의 내용, 반려 판단 근거를 기록하세요." onChange={event => setReason(event.target.value)} /></Field>
      <Field label="근거 문서·관리번호" required={context.data?.rejectionRequiresEvidence}
        helper="환수·상계 건은 통지서와 확정한 차감 관리번호를 함께 남기세요."><input data-guide-feature="features.exception.review.evidence" value={evidence} maxLength={500} disabled={busy} onChange={event => setEvidence(event.target.value)} /></Field>
      {context.data?.administratorReviewRequired && !administrator && <p>보험사 지급기준 한도 초과는 GA관리자에게 배정해 확인받아야 반려할 수 있습니다.</p>}
      {error && <p role="alert" className="field-error">{error}</p>}
      <div className="exception-review-actions">
        {context.data?.recheckAvailable && user?.canProcess && <Button data-guide-feature="features.exception.review.recheck" variant="secondary" disabled={busy || Boolean(reason || evidence)} loading={recheck.isPending} onClick={() => recheck.mutate()}>원천 재검증</Button>}
        <Button data-guide-feature="features.exception.review.comment" variant="secondary" disabled={busy || !reason.trim()} onClick={() => mutation.mutate('COMMENT')}>의견 저장 · 검토중 유지</Button>
        <Button data-guide-feature="features.exception.review.reject" disabled={rejectionDisabled} onClick={() => mutation.mutate('REJECT')}>근거 확인 · 반려 종결</Button>
      </div>
    </div>
  </section>
}
