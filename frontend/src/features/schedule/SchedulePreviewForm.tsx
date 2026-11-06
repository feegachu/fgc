import { useUnsavedWork } from '../../stores/unsavedWork'
import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useLocation, useNavigate } from 'react-router'
import { ScreenHelp } from '../assistant/guide/ScreenHelp'
import { SaveGuide } from '../assistant/guide/save-guide'
import { Button } from '../../components/Button'
import { ApiError } from '../../lib/api/client'
import { usePermission } from '../auth/useAuth'
import { errorText } from '../../lib/format'
import { returnLink, safeReturnTo } from '../transaction/payment-entry'
import { generatePreview, REASON_MAX } from './api'
import type { PaymentStage, PreviewContext } from './api'
import './schedule.css'

type Props = { context?: PreviewContext; contractId: string; stage: PaymentStage; scheduleId?: string }
type Attempt = {
  payload: string
  key: string
  body: { reason: string; regime: 'FOUR_YEAR_2027' | 'SEVEN_YEAR_2029'; policyVersionId: number; expectedSourceVersion: string }
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
  const [saveGuide] = useState(() => new SaveGuide())
  const saveState = useSyncExternalStore(saveGuide.subscribe, saveGuide.getSnapshot)
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
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; saveGuide.deactivate(false) } }, [saveGuide])
  const policy = context?.policy
  const resolution = policy?.selection?.resolution
  const policyId = policy?.commissionPolicy?.policyVersionId
  const regime = resolution?.declaredFeeRegimeCode
  const supported = regime === 'FOUR_YEAR_2027' || regime === 'SEVEN_YEAR_2029'
  const ruleset = policy?.selection?.ruleset
  const longTermReady = ruleset?.longTermMaintenanceFrom != null && ruleset?.longTermMaintenanceTo != null && policy?.commissionPolicy?.rules?.some(rule => rule.feeComponentType === 'LONG_TERM_MAINTENANCE')
  const ready = supported && resolution?.status === 'SELECTED' && Number.isSafeInteger(policyId) && Number(policyId) > 0 && !!context?.expectedSourceVersion
  const validReason = !!reason.trim() && reason.trim().length <= REASON_MAX
  const mutation = useMutation({
    mutationFn: (attempt: Attempt) => generatePreview(contractId, stage, attempt.body, attempt.key, scheduleId),
    meta: { errorToast: false },
    onSuccess: async (id, attempt) => {
      clearUnsaved()
      if (mounted.current) setServerSaved(true)
      // Mark old results stale even if the user left before the successful response.
      // Refetch after the guide finishes so deactivating the old header cannot remove its card.
      void client.invalidateQueries({ queryKey: ['schedules'], refetchType: 'none' })
      void client.invalidateQueries({ queryKey: ['contracts'], refetchType: 'none' })
      if (!mounted.current || locationKey.current !== attempt.locationKey) return
      // 안내 중에는 서버 성공을 표시하고 사용자가 안내를 마친 뒤 결과 화면으로 이동한다.
      const continueNavigation = await saveGuide.saved(id)
      if (continueNavigation && mounted.current && locationKey.current === attempt.locationKey)
        void navigate(returnLink(`/schedules/${id}`, attempt.back, attempt.back.split('?')[0] === '/schedules' ? String(id) : undefined))
      await Promise.all([
        client.invalidateQueries({ queryKey: ['schedules'] }),
        client.invalidateQueries({ queryKey: ['contracts'] }),
      ])
    },
    onError: (error) => {
      if (!mounted.current) return
      saveGuide.fail(errorText(error))
      // 통신/5xx/불완전한 성공 응답은 저장 여부가 불명확하므로 동일 요청으로 확인한다.
      setUncertain(!(error instanceof ApiError && error.status >= 400 && error.status < 500 && ![408, 429].includes(error.status)))
    },
    onSettled: () => { submitting.current = false },
  })
  useEffect(() => {
    saveGuide.update(uncertain ? request.current?.payload : JSON.stringify([contractId, stage, scheduleId, reason, policyId, regime, context?.expectedSourceVersion]), canProcess && (uncertain || (ready && validReason)))
  }, [saveGuide, uncertain, contractId, stage, scheduleId, reason, policyId, regime, context?.expectedSourceVersion, canProcess, ready, validReason])
  const displayRegime = uncertain ? mutation.variables?.body.regime : regime
  const years = displayRegime === 'SEVEN_YEAR_2029' ? '7년' : '4년'
  const unresolvedRegime = !resolution?.declaredFeeRegimeCode && resolution?.status === 'REVIEW_REQUIRED'
  if (!supported && !unresolvedRegime && !uncertain) return null

  function submit() {
    if (!canProcess || submitting.current || (!uncertain && (!ready || !validReason || !supported))) return
    if (!uncertain) {
      const body = { reason: reason.trim(), regime: regime as Attempt['body']['regime'], policyVersionId: policyId!, expectedSourceVersion: context!.expectedSourceVersion! }
      const payload = JSON.stringify(body)
      if (request.current?.payload !== payload) request.current = {
        body, payload, key: crypto.randomUUID(), locationKey: location.key,
        back: scheduleId ? safeReturnTo(new URLSearchParams(location.search).get('returnTo'), '/schedules') : `${location.pathname}${location.search}`,
      }
    }
    if (!request.current) return
    submitting.current = true
    saveGuide.begin()
    mutation.mutate(request.current)
  }

  return (
    <section data-guide-feature="schedule.preview.region" data-guide-scope className="surface schedule-detail-card schedule-preview-card" aria-label={`${stage} ${years} 예정 생성`}>
      <header className="schedule-preview-header">
        <h3 className="surface-title">{unresolvedRegime && !uncertain ? '적용 체계·정책 검토 필요' : `${years} 예정 스케줄`}</h3>
        {canProcess && (scheduleId
          ? <ScreenHelp screenId="SCHE-W02" variant="preview" saveGuide={saveGuide} />
          : <ScreenHelp screenId="CONT-W02" variant="preview" saveGuide={saveGuide} />)}
      </header>
      <p>정책에 정의된 회차와 금액으로 생성합니다. 지급 확정 대상이 아닙니다.</p>
      {uncertain ? <p>요청한 정책 #{mutation.variables?.body.policyVersionId}의 생성 결과를 확인합니다.</p> : ready ? (
        <p>선택 정책 #{policyId} · v{policy?.versionNo} · 최대 {policy?.selection?.ruleset?.maxInstallments}회차 · 근거 {policy?.sourceRefs?.join(', ')}</p>
      ) : <p role="status">정책 검토 필요: {resolution?.reasonCode ?? '적용 근거 없음'}</p>}
      {regime === 'SEVEN_YEAR_2029' && ready && !uncertain && (
        <p role="status">{longTermReady
          ? `장기유지 예정 구간: 정책 정의 ${ruleset?.longTermMaintenanceFrom}~${ruleset?.longTermMaintenanceTo}회차. 지급 자격 검증 결과가 아닙니다.`
          : '장기유지 계산 미준비: 정책 구간 또는 수수료 규칙이 없습니다. 정의된 일반 예정만 생성하며 장기유지 금액을 0원으로 채우지 않습니다.'}</p>
      )}
      {canProcess && (
        <form data-guide-feature="schedule.preview.form" className="schedule-preview-form" onSubmit={(event) => { event.preventDefault(); submit() }}>
          <label className="filter-field" data-guide-target="schedule.preview.input">
            생성 사유
            <input data-guide-feature="schedule.preview.reason" className="field-control" value={reason} onChange={(event) => setReason(event.target.value)} maxLength={REASON_MAX} required disabled={mutation.isPending || uncertain} />
          </label>
          <Button data-guide-feature="schedule.preview.submit" data-guide-target="schedule.preview.submit" type="submit" disabled={mutation.isPending || (!uncertain && (!ready || !validReason))} loading={mutation.isPending && saveState.pending}>
            {uncertain ? '생성 결과 다시 확인' : `${years} 예정 ${scheduleId ? '새 버전 만들기' : '생성'}`}
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
