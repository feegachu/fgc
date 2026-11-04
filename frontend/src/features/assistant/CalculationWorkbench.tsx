import { useEffect, useId, useRef, useState } from 'react'
import { apiClient } from '../../lib/api/client'
import type { components } from '../../lib/api/schema'
import { isWonAmount } from './guards'
import './payment-calculation.css'
type Snapshot = components['schemas']['PaymentCalculationSnapshot']
type Input = components['schemas']['PaymentCalculationRequest']
const labels: Record<string, string> = {
  expectedAmount: '예상액',
  actualAmount: '실제액',
  amount: '지급액',
  attributedTotal: '귀속 합계',
  differenceAmount: '차액',
  limitAmount: '한도',
  includedAmount: '산입액',
  remainingAmount: '잔여 한도',
  candidateAmount: '후보 산입액',
}
const won = (value: unknown) => (isWonAmount(value) ? `${BigInt(value).toLocaleString('ko-KR')}원` : '확인 필요')
function CalculationField<Name extends string>({
  label,
  type,
  value,
  featureId,
  name,
  onChange,
}: {
  label: string
  type: string
  value: string
  featureId: string
  name: Name
  onChange: (name: Name, value: string) => void
}) {
  return (
    <label>
      {label}
      <input
        data-guide-feature={featureId}
        type={type}
        value={value}
        onChange={(event) => onChange(name, event.target.value)}
      />
    </label>
  )
}
export function CalculationWorkbench({
  canProcess,
  candidate,
  paymentId,
  revision = 0,
  onApply,
}: {
  canProcess: boolean
  candidate?: () => Record<string, unknown>
  paymentId?: string | null
  revision?: number
  onApply?: (amount: string, scope: NonNullable<Input['scope']>, snapshot: Snapshot) => boolean
}) {
  const instance = useId().replaceAll(':', '')
  const [expanded, setExpanded] = useState(false)
  const [kind, setKind] = useState<Input['kind']>('EXPECTED_COMMISSION')
  const [fields, setFields] = useState({
    contractId: '',
    settlementMonth: '',
    paymentStage: 'GA_TO_FC',
    commissionItemId: '',
    beneficiaryAgentId: '',
    installmentNo: '',
    asOfDate: '',
    reconciliationResultId: '',
    aggregation: 'SINGLE_LINE',
    amount: '',
    attributionAmounts: '',
  })
  const [result, setResult] = useState<Snapshot | null>(null),
    [request, setRequest] = useState<Input | null>(null),
    [error, setError] = useState(''),
    [busy, setBusy] = useState(false)
  const pending = useRef<AbortController | null>(null),
    sequence = useRef(0)
  useEffect(
    () => () => {
      pending.current?.abort()
      sequence.current++
    },
    [],
  )
  function changed() {
    pending.current?.abort()
    sequence.current++
    setBusy(false)
    setResult(null)
    setRequest(null)
    setError('')
  }
  function field(name: keyof typeof fields, value: string) {
    changed()
    setFields({ ...fields, [name]: value })
  }
  async function run(recheck = false, apply = false) {
    const controller = new AbortController(),
      current = ++sequence.current
    pending.current?.abort()
    pending.current = controller
    setBusy(true)
    setError('')
    try {
      let input: Input
      if (recheck) {
        if (!request || !result?.validity?.stateToken) throw new Error('현재 근거로 다시 계산해주세요.')
        input = request
      } else {
        const scope: NonNullable<Input['scope']> = {}
        let payload: Record<string, unknown> | null = null
        let mode: Input['mode'] = 'EXISTING'
        if (kind === 'ATTRIBUTION_BALANCE') {
          mode = 'HYPOTHETICAL'
          payload = {
            balance: {
              amount: fields.amount,
              attributionAmounts: fields.attributionAmounts.split(/[,\n]/).map((value) => value.trim()),
            },
          }
        } else if (kind === 'PAYMENT_CANDIDATE') {
          if (!candidate || !canProcess) throw new Error('지급 입력 화면에서 후보를 검토해주세요.')
          mode = 'HYPOTHETICAL'
          payload = { operation: paymentId ? 'REPLACE_DRAFT' : 'NEW_PAYMENT', payment: candidate() }
          if (paymentId) scope.paymentId = paymentId
        } else if (kind === 'RECONCILIATION_DIFFERENCE') scope.reconciliationResultId = fields.reconciliationResultId
        else {
          scope.contractId = fields.contractId
          scope.paymentStage = fields.paymentStage as 'GA_TO_FC' | 'INSURER_TO_GA'
          if (kind === 'CAP_STATUS') scope.asOfDate = fields.asOfDate
          else {
            scope.settlementMonth = fields.settlementMonth
            scope.aggregation = fields.aggregation as 'SINGLE_LINE' | 'MONTH_TOTAL'
            if (fields.commissionItemId) scope.commissionItemId = fields.commissionItemId
            if (fields.beneficiaryAgentId) scope.beneficiaryAgentId = fields.beneficiaryAgentId
            if (fields.installmentNo) scope.installmentNo = Number(fields.installmentNo)
          }
        }
        input = {
          contractVersion: 'assistant.v1',
          kind,
          mode,
          scope: kind === 'ATTRIBUTION_BALANCE' ? undefined : scope,
          candidate: payload,
          clientRevision: revision,
        }
      }
      const response = await apiClient.request<Snapshot>(
        recheck ? '/api/v1/assistant/calculations/revalidate' : '/api/v1/assistant/calculations',
        {
          method: 'POST',
          signal: controller.signal,
          body: recheck
            ? { calculationId: result!.calculationId, stateToken: result!.validity!.stateToken, input }
            : input,
        },
      )
      if (controller.signal.aborted || current !== sequence.current) return
      const next = response.data
      if (
        !next ||
        next.synthetic !== false ||
        next.clientRevision !== revision ||
        next.kind !== input.kind ||
        next.currency !== 'KRW' ||
        Object.values(next.amounts ?? {}).some((value) => value != null && !isWonAmount(value))
      )
        throw new Error('유효한 계산 결과를 받지 못했습니다.')
      setResult(next)
      setRequest(input)
      if (apply) {
        if (
          next.status !== 'CALCULATED' ||
          !next.validity ||
          !next.recommendation?.allowed ||
          !isWonAmount(next.recommendation.suggestedAmount) ||
          !input.scope ||
          !onApply?.(next.recommendation.suggestedAmount, input.scope, next)
        )
          throw new Error('현재 입력 대상과 예상액 근거가 일치하지 않습니다. 계약·수령자·항목·회차를 확인해주세요.')
        setError('예상액을 입력했습니다. 실제 명세를 대조한 후 저장·사전검증을 직접 진행하세요.')
      }
    } catch (e) {
      if (!controller.signal.aborted && current === sequence.current) {
        setResult(null)
        setError(e instanceof Error ? e.message : '계산을 다시 확인해주세요.')
      }
    } finally {
      if (!controller.signal.aborted && current === sequence.current) setBusy(false)
    }
  }
  const text = (name: keyof typeof fields, label: string, type = 'text') => (
    <CalculationField
      label={label}
      type={type}
      value={fields[name]}
      featureId={`assistant.calculation.${instance}.${name}`}
      name={name}
      onChange={field}
    />
  )
  return (
    <details
      className="payment-calculation"
      onToggle={(event) => {
        setExpanded(event.currentTarget.open)
        if (!event.currentTarget.open) changed()
      }}
    >
      <summary>업무 계산 도우미</summary>
      {expanded && (
        <>
          <p>기존 프로그램의 계산 근거를 조회합니다. 저장·지급 확정은 직접 진행하세요.</p>
          <label>
            계산 업무
            <select
              data-guide-feature={`assistant.calculation.${instance}.kind`}
              value={kind}
              onChange={(event) => {
                changed()
                setKind(event.target.value as Input['kind'])
              }}
            >
              <option value="EXPECTED_COMMISSION">예상 수수료</option>
              <option value="CAP_STATUS">현재 한도</option>
              <option value="RECONCILIATION_DIFFERENCE">저장된 대사 차액</option>
              <option value="ATTRIBUTION_BALANCE">입력 금액 귀속 차액</option>
              {candidate && canProcess && <option value="PAYMENT_CANDIDATE">현재 입력의 지급 후보 검토</option>}
            </select>
          </label>
          {(kind === 'EXPECTED_COMMISSION' || kind === 'CAP_STATUS') && (
            <>
              {text('contractId', '계약 번호')}
              <label>
                지급 단계
                <select
                  data-guide-feature={`assistant.calculation.${instance}.stage`}
                  value={fields.paymentStage}
                  onChange={(event) => field('paymentStage', event.target.value)}
                >
                  <option value="GA_TO_FC">GA → 설계사</option>
                  <option value="INSURER_TO_GA">보험사 → GA</option>
                </select>
              </label>
            </>
          )}
          {kind === 'EXPECTED_COMMISSION' && (
            <>
              {text('settlementMonth', '정산월', 'month')}
              <label>
                조회 범위
                <select
                  data-guide-feature={`assistant.calculation.${instance}.aggregation`}
                  value={fields.aggregation}
                  onChange={(event) => field('aggregation', event.target.value)}
                >
                  <option value="SINGLE_LINE">단일 지급 행</option>
                  <option value="MONTH_TOTAL">월 합계</option>
                </select>
              </label>
              {text('commissionItemId', '수수료 항목 번호')}
              {text('beneficiaryAgentId', '수령 설계사 번호')}
              {text('installmentNo', '회차')}
            </>
          )}
          {kind === 'CAP_STATUS' && text('asOfDate', '한도 기준일', 'date')}
          {kind === 'RECONCILIATION_DIFFERENCE' && text('reconciliationResultId', '대사 결과 번호')}
          {kind === 'ATTRIBUTION_BALANCE' && (
            <>
              {text('amount', '지급액')}
              {text('attributionAmounts', '귀속 금액 (쉼표로 구분)')}
            </>
          )}
          {kind === 'PAYMENT_CANDIDATE' && (
            <p>
              {paymentId
                ? `초안 #${paymentId}를 현재 입력으로 대체해 검토합니다.`
                : '현재 입력을 신규 지급 후보로 검토합니다.'}{' '}
              실제 저장하지 않습니다.
            </p>
          )}
          <div className="payment-calculation-actions">
            <button
              data-guide-feature={`assistant.calculation.${instance}.run`}
              type="button"
              disabled={busy}
              onClick={() => void run()}
            >
              계산 조회
            </button>
            {result?.validity && (
              <button
                data-guide-feature={`assistant.calculation.${instance}.revalidate`}
                type="button"
                disabled={busy}
                onClick={() => void run(true)}
              >
                최신 근거 재확인
              </button>
            )}
            {result?.recommendation?.allowed && onApply && (
              <button
                data-guide-feature={`assistant.calculation.${instance}.apply`}
                type="button"
                disabled={busy}
                onClick={() => void run(true, true)}
              >
                재확인 후 지급액에 반영
              </button>
            )}
          </div>
          {busy && <p role="status">계산 근거를 확인하고 있어요.</p>}
          {error && <p role="status">{error}</p>}
          {result && (
            <section aria-label="업무 계산 결과" aria-live="polite">
              <strong>
                {result.status === 'STALE'
                  ? '계산 만료 · 다시 계산하세요'
                  : result.status === 'CALCULATED'
                    ? '계산 완료'
                    : result.status === 'REVIEW_REQUIRED'
                      ? '검토 필요'
                      : '자료 확인 필요'}
              </strong>
              <p>
                {result.evidenceKind === 'HYPOTHETICAL'
                  ? '가상 후보'
                  : result.evidenceKind === 'EXPECTED'
                    ? '예상 스케줄'
                    : '저장된 실제 자료'}{' '}
                · 실제 사전검증 완료를 뜻하지 않습니다.
              </p>
              <dl>
                {Object.entries(result.amounts ?? {})
                  .filter(([key]) => key in labels)
                  .map(([key, value]) => (
                    <div key={key}>
                      <dt>{labels[key]}</dt>
                      <dd>{won(value)}</dd>
                    </div>
                  ))}
              </dl>
              {result.lines?.map((line) => (
                <p key={line.sourceId}>
                  계약 #{line.contractId} · 항목 #{line.commissionItemId} · {line.installmentNo}회차 · 귀속일{' '}
                  {line.attributionDate} · 지급예정일 {line.dueDate} · 예상 {won(line.expectedAmount)} · 기준{' '}
                  {line.basisCode} {line.basisAmount} · 요율 {line.ratePct}% · 규칙 #{line.ruleId}
                </p>
              ))}
              {result.capPreview?.map((row, index) => (
                <p key={index}>
                  계약 #{String(row.contractId ?? '미확인')} · 한도 {won(row.limitAmount)} · 산입{' '}
                  {won(row.includedAmount)} · 잔여 {won(row.remainingAmount)} ·{' '}
                  {String(row.resultStatusLabel ?? row.resultStatus ?? '미확인')} · 사용률{' '}
                  {row.usagePct == null ? '미제공' : `${String(row.usagePct)}%`}
                </p>
              ))}
              {result.blockers?.map((blocker, index) => (
                <p key={index}>{blocker.message}</p>
              ))}
              {!!result.missingInputs?.length && <p>추가 확인: {result.missingInputs.join(', ')}</p>}
              <p>
                기준일 {result.basis?.asOfDate ?? '행별 확인'} · 정책{' '}
                {result.basis?.policyVersionIds?.join(', ') || '미제공'} · 규칙{' '}
                {result.basis?.ruleIds?.join(', ') || '미제공'} · 반올림 {result.basis?.rounding?.mode} /{' '}
                {result.basis?.rounding?.aggregation}
              </p>
              {result.basis?.sourceRefs?.map((source) => (
                <p key={source.sourceId}>
                  근거 {source.title} #{source.entityId} · {source.version}
                </p>
              ))}
              <p>{result.recommendation?.reason}</p>
              {result.validity && <small>유효 시각 {result.validity.expiresAt} · 사용 전 최신 근거 재확인</small>}
            </section>
          )}
        </>
      )}
    </details>
  )
}
