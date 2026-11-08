import { useIsMutating, useMutation, useQueryClient } from '@tanstack/react-query'
import { useContext, useState } from 'react'
import { createPortal } from 'react-dom'
import { DisabledButtonHint } from '../../components/DisabledButtonHint'
import { ClawbackActionPlacement } from './actionPlacement'
import type { FormEvent } from 'react'
import { Button } from '../../components/Button'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { Field } from '../../components/Field'
import { StatusBadge } from '../../components/StatusBadge'
import { ApiError } from '../../lib/api/client'
import { date, dateTime, errorText, int, internalErrorText, rate } from '../../lib/format'
import { errorScreenMessage } from '../../lib/screenText'
import { toast } from '../../stores/toasts'
import { useUnsavedWork } from '../../stores/unsavedWork'
import { present } from '../arbitrage/utils'
import { usePolicyDetail } from '../policies/api'
import { usePolicyRequest } from '../policies/usePolicyRequest'
import { calculateCandidate } from './api'
import type { Calculation, CandidateDetail } from './api'
import { Amount } from './badges'
import { policyLabel, reasonText } from './labels'

type Line = NonNullable<Calculation['lines']>[number]
type Excluded = { commissionItemId?: number; reason?: string }
const REASON_MAX = 500

/** 계산 실패 안내. 실패를 0원으로 보이지 않고, 중단 사유는 코드 대신 문구로 쓴다(공통규칙 10). */
function failureText(error: unknown) {
  if (!(error instanceof ApiError))
    return '연결이 끊겨 계산 결과를 확인하지 못했습니다. 같은 내용으로 다시 누르면 중복 없이 이어서 처리합니다.'
  if (error.status >= 500 || error.code === 'FGC-COMMON-500') return internalErrorText(error.requestId)
  if (error.code === 'FGC-IMPL-POLICY_MISSING') {
    const reasons = Array.isArray(error.params.reasons) ? error.params.reasons.map(String) : []
    const detail = reasons.length ? reasons.map(reasonText).join(', ') : '적용 정책과 기지급 내역을 확인하세요'
    return errorScreenMessage(`계산 근거가 부족해 계산하지 않았습니다. ${detail}.`, error.requestId)
  }
  if (error.code === 'FGC-IMPL-STATE_CHANGED')
    return errorScreenMessage(
      '조회한 뒤 후보 상태나 근거가 바뀌었습니다. 최신 내용을 다시 불러옵니다. 확인한 뒤 다시 계산하세요.',
      error.requestId,
    )
  if (error.code === 'FGC-IMPL-IDEMPOTENCY_CONFLICT')
    return errorScreenMessage('같은 계산 요청을 다른 내용으로 다시 보냈습니다. 화면을 새로 고친 뒤 다시 계산하세요.', error.requestId)
  if (error.code === 'FGC-IMPL-REQUEST_IN_PROGRESS')
    return errorScreenMessage('같은 계산을 처리하고 있습니다. 잠시 뒤 다시 누르세요.', error.requestId)
  if (error.status === 403) return errorScreenMessage('환수액을 계산할 권한이 없습니다.', error.requestId)
  return errorText(error, '환수액을 계산하지 못했습니다.')
}

export function ClawbackCalculation({
  detail,
  itemName,
}: {
  detail: CandidateDetail
  itemName: (commissionItemId?: number) => string
}) {
  const client = useQueryClient()
  const placement = useContext(ClawbackActionPlacement)
  const c = detail.candidate ?? {}
  const policies = detail.policyCandidates ?? []
  const canCalculate = c.allowedActions?.includes('CALCULATE') ?? false
  const issuingNotice = useIsMutating({ mutationKey: ['clawback', 'issue-notice', c.clawbackCaseId] }) > 0
  // 정책 후보가 하나면 미리 고르고, 여럿이면 사용자가 고른다. 계산 버튼을 누르는 것이 선택 확인이다.
  // 고른 값은 지금 후보에 있을 때만 쓴다(409 뒤 다시 불러온 후보에서 빠질 수 있다).
  const [pickedPolicyId, setPickedPolicyId] = useState<number | null>(null)
  const policyId =
    policies.find((p) => p.policyVersionId === pickedPolicyId)?.policyVersionId ??
    (policies.length === 1 ? (policies[0].policyVersionId ?? null) : null)
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const keyFor = usePolicyRequest()
  const clearUnsaved = useUnsavedWork(Boolean(reason.trim()), reason)
  const trimmed = reason.trim()
  const ready = canCalculate && !issuingNotice && policyId != null && trimmed.length > 0 && Boolean(c.sourceVersion)
  const mutation = useMutation({
    mutationKey: ['clawback', 'calculate', c.candidateId],
    mutationFn: () => {
      const body = { expectedSourceVersion: c.sourceVersion!, policyVersionId: policyId!, reason: trimmed }
      // 응답을 못 받은 재시도는 같은 키로 보내 계산판이 두 번 생기지 않게 한다. 입력이 바뀌면 새 키다.
      return calculateCandidate(c.candidateId!, body, keyFor(body))
    },
    meta: { errorToast: false },
    onSuccess: (calculation) => {
      clearUnsaved()
      setReason('')
      setError('')
      toast(`환수액을 계산했습니다. 계산판 v${int(calculation.calculationVersion)}입니다.`, 'success')
      void client.invalidateQueries({ queryKey: ['clawback'] })
    },
    onError: (caught) => {
      setError(failureText(caught))
      if (caught instanceof ApiError && caught.code === 'FGC-IMPL-STATE_CHANGED')
        void client.invalidateQueries({ queryKey: ['clawback', 'candidate', c.candidateId] })
    },
  })
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!ready || mutation.isPending) return
    setError('')
    mutation.mutate()
  }
  const helper = !canCalculate
    ? '계산할 수 없는 후보입니다. 위 검토 사유를 확인하세요.'
    : !policies.length
      ? '적용할 환수 정책이 없어 계산할 수 없습니다.'
      : policyId == null
        ? '적용할 정책을 고르세요.'
        : '선택한 정책과 기지급 내역으로 계산합니다. 금액과 환수율은 직접 입력하지 않습니다.'
  const formId = `claw-calculation-${c.candidateId}`
  const reasonText = mutation.isPending ? '환수액을 계산하고 있습니다.' : issuingNotice ? 'PDF 발행을 마친 뒤 계산할 수 있습니다.'
    : !canCalculate || !policies.length || policyId == null ? helper : !trimmed ? '계산 사유를 입력하세요.'
      : !c.sourceVersion ? '최신 원천을 다시 조회하세요.' : ''
  const action = <DisabledButtonHint disabled={!ready || mutation.isPending} reason={reasonText}>
    <Button data-guide-feature="features.clawback.clawback-detail.button" type="submit" form={formId}
      disabled={!ready} loading={mutation.isPending}>환수액 계산</Button>
  </DisabledButtonHint>
  return (
    <section
      data-guide-feature="features.clawback.clawback-detail.section-2"
      className="claw-evidence claw-calculation"
      aria-label="환수 계산"
    >
      <h3 className="claw-evidence-title">
        환수 계산 <StatusBadge tone="info">회사 정책</StatusBadge>
      </h3>
      {!policies.length && <p className="claw-evidence-empty">적용 가능한 환수 정책 버전이 없습니다.</p>}
      <form
        id={formId}
        data-guide-feature="features.clawback.clawback-calculation.form"
        className="claw-calculation-form"
        aria-label="환수액 계산"
        onSubmit={submit}
      >
        {policies.length > 0 && (
          // 좁은 상세 칸에서도 정책 이름과 유효 기간이 잘리지 않게 표 대신 선택지로 늘어놓는다.
          <fieldset className="claw-policy-options" disabled={!canCalculate || mutation.isPending || issuingNotice}>
            <legend className="field-label">
              적용할 정책
              <span className="field-required" aria-hidden="true">
                {' '}
                *
              </span>
            </legend>
            {policies.map((r) => (
              <label key={r.policyVersionId} className="claw-policy-option">
                <input
                  data-guide-feature="features.clawback.clawback-calculation.policy"
                  type="radio"
                  className="claw-policy-select"
                  name={`claw-policy-${c.candidateId}`}
                  checked={policyId === r.policyVersionId}
                  onChange={() => {
                    // 입력이 바뀌면 이전 오류는 맞지 않는다(멱등키도 바뀐다). 다음 계산에서 다시 판정한다.
                    setError('')
                    setPickedPolicyId(r.policyVersionId ?? null)
                  }}
                />
                <span className="claw-policy-option-text">
                  <strong>{policyLabel(r)}</strong>{' '}
                  <span>
                    {present(r.policyName)}, 유효 기간 {date(r.effectiveFrom)}
                    {r.effectiveTo ? ` ~ ${date(r.effectiveTo)}` : '부터'}
                  </span>
                </span>
              </label>
            ))}
          </fieldset>
        )}
        <Field label="계산 사유" required helper={`${int(reason.length)}/${REASON_MAX}자`}>
          <textarea
            data-guide-feature="features.clawback.clawback-calculation.reason"
            className="textarea-control"
            rows={2}
            maxLength={REASON_MAX}
            placeholder="예: 등록된 환수 정책으로 계산"
            value={reason}
            disabled={!canCalculate || mutation.isPending || issuingNotice}
            onChange={(event) => {
              setError('')
              setReason(event.target.value)
            }}
          />
        </Field>
        {error && (
          <p className="field-error" role="alert">
            {error}
          </p>
        )}
        <div className="claw-calculation-actions">
          {placement.revised ? placement.calculation && createPortal(action, placement.calculation) : action}
          <p className="field-helper">{helper}</p>
        </div>
      </form>
      <CalculationResult detail={detail} itemName={itemName} />
    </section>
  )
}

function CalculationResult({
  detail,
  itemName,
}: {
  detail: CandidateDetail
  itemName: (commissionItemId?: number) => string
}) {
  const calc = detail.latestCalculation
  const candidatePolicy = detail.policyCandidates?.find((p) => p.policyVersionId === calc?.policyVersionId)
  // 계산판 응답에는 정책판 번호만 있다. 그 정책이 지금 후보에서 빠졌으면(새 판 승인 등) 정책 상세에서 코드와 판을 찾는다.
  const usedPolicy = usePolicyDetail(calc?.policyVersionId, Boolean(calc) && !candidatePolicy)
  if (!calc) return <p className="claw-evidence-empty">저장된 계산판이 없습니다.</p>
  const policy = candidatePolicy ?? usedPolicy.data?.header
  const policyText = policy?.policyCode
    ? policyLabel(policy)
    : usedPolicy.isLoading
      ? '적용 정책 확인 중'
      : '적용 정책 확인 필요'
  const basis = (calc.basis ?? {}) as Record<string, unknown>
  const excluded = Array.isArray(basis.excluded) ? (basis.excluded as Excluded[]) : []
  const stale = calc.sourceVersion !== detail.candidate?.sourceVersion
  // 좁은 상세 칸에서도 금액이 잘리지 않게 금액 열 폭(8자리 금액까지)을 정하고 남는 폭을 항목이 쓴다.
  // 항목이 말줄임되면 마우스를 올려 전체를 본다. 경과월은 후보 단위라 요약 줄에 쓴다.
  const itemText = (r: Line) =>
    r.installmentNo == null ? itemName(r.commissionItemId) : `${itemName(r.commissionItemId)}, ${int(r.installmentNo)}회차`
  const columns: TableColumn<Line>[] = [
    { key: 'item', label: '수수료 항목', render: (r) => <span title={itemText(r)}>{itemText(r)}</span> },
    { key: 'basis', label: '기지급 기준액', align: 'number', width: '7rem', render: (r) => <Amount value={r.basisAmount} /> },
    { key: 'rate', label: '환수율', align: 'number', width: '4.5rem', render: (r) => `${rate(r.appliedRatePct)}%` },
    { key: 'amount', label: '환수액', align: 'number', width: '7rem', render: (r) => <Amount value={r.clawbackAmount} /> },
  ]
  const elapsed = typeof basis.elapsedMonths === 'number' ? `, 경과월 ${int(basis.elapsedMonths)}개월` : ''
  return (
    <div className="claw-calculation-result">
      <p className="claw-calculation-summary">
        최신 계산판 v{int(calc.calculationVersion)}, {dateTime(calc.calculatedAt)}, {policyText}
        {elapsed}
      </p>
      {stale && (
        <p className="claw-calculation-stale" role="status">
          계산한 뒤 원천이나 정책이 바뀌었습니다. 이 계산판은 지금 근거와 다르니 다시 계산하세요.
        </p>
      )}
      {calc.lines?.length ? (
        <>
          <DataTable
            viewportClassName="claw-evidence-table claw-lines-table"
            caption="환수 계산 결과"
            rows={calc.lines}
            rowKey={(r) => `${r.transactionAttributionId ?? 'x'}-${r.clawbackRuleId ?? 'x'}`}
            columns={columns}
          />
          {/* 상세 칸이 좁으면(25rem 미만) 표 대신 줄마다 항목과 금액을 늘어놓는다. 금액과 항목이 모두 잘리지 않는다. */}
          <ul className="claw-lines-list" aria-label="환수 계산 결과">
            {calc.lines.map((r) => (
              <li key={`${r.transactionAttributionId ?? 'x'}-${r.clawbackRuleId ?? 'x'}`}>
                <strong>{itemText(r)}</strong>
                <span>
                  기지급 기준액 <Amount value={r.basisAmount} />
                </span>
                <span>환수율 {rate(r.appliedRatePct)}%</span>
                <span>
                  환수액 <Amount value={r.clawbackAmount} />
                </span>
              </li>
            ))}
          </ul>
        </>
      ) : (
        <p className="claw-evidence-empty">환수 대상 항목이 없습니다.</p>
      )}
      <p className="claw-calculation-total">
        환수액 합계 <strong><Amount value={calc.amount} /></strong>
      </p>
      {excluded.length > 0 && (
        <p className="field-helper">
          {reasonText('NO_CLAWBACK_RULE')}이라 계산에서 뺐습니다:{' '}
          {excluded.map((e) => itemName(e.commissionItemId)).join(', ')}
        </p>
      )}
      {typeof basis.reason === 'string' && basis.reason && <p className="field-helper">계산 사유: {basis.reason}</p>}
      <p className="field-helper">
        계산 결과는 환수 확정이나 회수 완료가 아닙니다. 경과월은 계약일부터 사건일까지 다 채운 달 수이고, 환수액은
        행마다 원 단위로 반올림합니다.
      </p>
    </div>
  )
}
