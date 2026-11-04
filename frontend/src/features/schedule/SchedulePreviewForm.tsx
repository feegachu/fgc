import { useUnsavedWork } from '../../stores/unsavedWork'
import { useEffect, useRef, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useLocation, useNavigate } from 'react-router'
import { Button } from '../../components/Button'
import { ApiError } from '../../lib/api/client'
import { usePermission } from '../auth/useAuth'
import { errorText } from '../../lib/format'
import { returnLink, safeReturnTo } from '../transaction/payment-entry'
import { generatePreview, REASON_MAX } from './api'
import type { PaymentStage, PreviewContext } from './api'

type Props = { context?: PreviewContext; contractId: string; stage: PaymentStage; scheduleId?: string }
type Attempt = {
  payload: string
  key: string
  body: { reason: string; regime: 'FOUR_YEAR_2027'; policyVersionId: number; expectedSourceVersion: string }
  locationKey: string
  back: string
}

/** 대상이 바뀌면 입력·멱등키도 분리한다. 기존 대상의 늦은 응답은 이동을 일으키지 않는다. */
export function SchedulePreviewForm(props: Props) {
  return <PreviewSession key={`${props.contractId}:${props.stage}:${props.scheduleId ?? ''}`} {...props} />
}

function PreviewSession({ context, contractId, stage, scheduleId }: Props) {
  const canProcess = usePermission('canProcess')
  const [reason, setReason] = useState('')
  const [uncertain, setUncertain] = useState(false)
  const [serverSaved, setServerSaved] = useState(false)
  const clearUnsaved = useUnsavedWork(canProcess && Boolean(reason) && !serverSaved, reason)
  const request = useRef<Attempt | null>(null)
  const submitting = useRef(false)
  const mounted = useRef(true)
  const client = useQueryClient()
  const navigate = useNavigate()
  const location = useLocation()
  const locationKey = useRef(location.key)
  useEffect(() => { locationKey.current = location.key }, [location.key])
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  const policy = context?.policy
  const resolution = policy?.selection?.resolution
  const policyId = policy?.commissionPolicy?.policyVersionId
  const fourYear = resolution?.declaredFeeRegimeCode === 'FOUR_YEAR_2027'
  const ready = fourYear && resolution?.status === 'SELECTED' && Number.isSafeInteger(policyId) && Number(policyId) > 0 && !!context?.expectedSourceVersion
  const validReason = !!reason.trim() && reason.trim().length <= REASON_MAX
  const mutation = useMutation({
    mutationFn: (attempt: Attempt) => generatePreview(contractId, stage, attempt.body, attempt.key, scheduleId),
    meta: { errorToast: false },
    onSuccess: async (id, attempt) => {
      clearUnsaved()
      if (mounted.current) setServerSaved(true)
      // Move before refetch deactivates the old header and unmounts this form.
      if (mounted.current && locationKey.current === attempt.locationKey)
        void navigate(returnLink(`/schedules/${id}`, attempt.back, attempt.back.split('?')[0] === '/schedules' ? String(id) : undefined))
      await Promise.all([
        client.invalidateQueries({ queryKey: ['schedules'] }),
        client.invalidateQueries({ queryKey: ['contracts'] }),
      ])
    },
    onError: (error) => {
      if (!mounted.current) return
      // 통신/5xx/불완전한 성공 응답은 저장 여부가 불명확하므로 동일 요청으로 확인한다.
      setUncertain(!(error instanceof ApiError && error.status >= 400 && error.status < 500 && ![408, 429].includes(error.status)))
    },
    onSettled: () => { submitting.current = false },
  })
  const unresolvedRegime = !resolution?.declaredFeeRegimeCode && resolution?.status === 'REVIEW_REQUIRED'
  if (!fourYear && !unresolvedRegime && !uncertain) return null

  function submit() {
    if (!canProcess || submitting.current || (!uncertain && (!ready || !validReason))) return
    if (!uncertain) {
      const body = { reason: reason.trim(), regime: 'FOUR_YEAR_2027' as const, policyVersionId: policyId!, expectedSourceVersion: context!.expectedSourceVersion! }
      const payload = JSON.stringify(body)
      if (request.current?.payload !== payload) request.current = {
        body, payload, key: crypto.randomUUID(), locationKey: location.key,
        back: scheduleId ? safeReturnTo(new URLSearchParams(location.search).get('returnTo'), '/schedules') : `${location.pathname}${location.search}`,
      }
    }
    if (!request.current) return
    submitting.current = true
    mutation.mutate(request.current)
  }

  return (
    <section data-guide-feature="schedule.preview.region" className="surface schedule-detail-card" aria-label={`${stage} 4년 예정 생성`}>
      <h3 className="surface-title">{unresolvedRegime ? '적용 체계·정책 검토 필요' : '4년 예정 스케줄'}</h3>
      <p>정책에 정의된 회차와 금액으로 생성합니다. 지급 확정 대상이 아닙니다.</p>
      {ready ? (
        <p>선택 정책 #{policyId} · v{policy?.versionNo} · 최대 {policy?.selection?.ruleset?.maxInstallments}회차 · 근거 {policy?.sourceRefs?.join(', ')}</p>
      ) : <p role="status">정책 검토 필요: {resolution?.reasonCode ?? '적용 근거 없음'}</p>}
      {canProcess && (
        <form data-guide-feature="schedule.preview.form" onSubmit={(event) => { event.preventDefault(); submit() }}>
          <label>
            생성 사유
            <input data-guide-feature="schedule.preview.reason" className="field-control" value={reason} onChange={(event) => setReason(event.target.value)} maxLength={REASON_MAX} required disabled={mutation.isPending || uncertain} />
          </label>
          <Button data-guide-feature="schedule.preview.submit" type="submit" disabled={!uncertain && (!ready || !validReason)} loading={mutation.isPending}>
            {uncertain ? '생성 결과 다시 확인' : `4년 예정 ${scheduleId ? '새 버전 만들기' : '생성'}`}
          </Button>
          {mutation.isError && (
            <p role="alert">
              {uncertain ? '생성 여부를 확인하지 못했습니다. 같은 요청으로 결과를 다시 확인하세요. 새 버전은 중복 생성하지 않습니다.' : errorText(mutation.error)}{' '}
              {!uncertain && <Button data-guide-feature="schedule.preview.refresh" type="button" variant="secondary" onClick={() => {
                void client.invalidateQueries({ queryKey: ['contracts'] })
                void client.invalidateQueries({ queryKey: ['schedules'] })
              }}>최신 입력 다시 조회</Button>}
            </p>
          )}
        </form>
      )}
    </section>
  )
}
