import { useEffect, useRef, useState } from 'react'
import { apiClient } from '../../lib/api/client'
import { dateTimeSeconds } from '../../lib/format'
import type { components } from '../../lib/api/schema'
import { isWonAmount } from './guards'
import type { PaymentObservation } from './guide-runtime'
import './payment-calculation.css'

type Snapshot = components['schemas']['PaymentCalculationSnapshot']
type Kind = 'ATTRIBUTION_BALANCE' | 'PAYMENT_CANDIDATE'
const names: Record<string, string> = {
  amount: '저장된 지급액',
  attributedTotal: '귀속 합계',
  differenceAmount: '지급액 − 귀속 합계',
  limitAmount: '한도',
  includedAmount: '산입액',
  remainingAmount: '잔여 한도',
  candidateAmount: '지급 후보 산입액',
}
const won = (value: unknown) => (isWonAmount(value) ? `${BigInt(value).toLocaleString('ko-KR')}원` : '확인 전')
/** Mounted with the form identity/revision key; never reuses a previous account or edited form response. */
export function PaymentCalculationPanel({
  observation,
  canProcess,
}: {
  observation: PaymentObservation
  canProcess: boolean
}) {
  const [result, setResult] = useState<Snapshot | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const pending = useRef<AbortController | null>(null)
  const sequence = useRef(0)
  useEffect(
    () => () => {
      pending.current?.abort()
      sequence.current++
    },
    [],
  )
  async function load(kind: Kind) {
    if (!observation.ready || !observation.saved || !observation.paymentId || observation.busy) return
    pending.current?.abort()
    const controller = new AbortController(),
      current = ++sequence.current
    pending.current = controller
    setBusy(true)
    setError('')
    setResult(null)
    try {
      const response = await apiClient.request<Snapshot>('/api/v1/assistant/calculations', {
        method: 'POST',
        signal: controller.signal,
        body: {
          contractVersion: 'assistant.v1',
          kind,
          mode: 'EXISTING',
          scope: { paymentId: observation.paymentId },
          candidate: null,
          clientRevision: observation.revision,
        },
      })
      if (controller.signal.aborted || current !== sequence.current) return
      const data = response.data
      if (
        !data ||
        data.synthetic !== false ||
        data.snapshotOnly !== true ||
        data.clientRevision !== observation.revision ||
        data.kind !== kind ||
        data.currency !== 'KRW' ||
        data.resolvedScope?.paymentId !== observation.paymentId ||
        data.validity != null
      )
        throw new Error('INVALID_SNAPSHOT')
      if (Object.values(data.amounts ?? {}).some((value) => value != null && !isWonAmount(value)))
        throw new Error('INVALID_MONEY')
      setResult(data)
    } catch {
      if (!controller.signal.aborted && current === sequence.current)
        setError('계산 결과를 조회하지 못했어요. 현재 지급 건과 서버 연결·권한을 확인하고 다시 조회하세요.')
    } finally {
      if (!controller.signal.aborted && current === sequence.current) setBusy(false)
    }
  }
  return (
    <details className="payment-calculation" open>
      <summary>현재 지급 건 계산 조회 {observation.paymentId ? `#${observation.paymentId}` : ''}</summary>
      <p>저장된 자료를 서버 계산기로 조회합니다. 시연 질문과 별개이며 자동 입력·확정하지 않아요.</p>
      {!observation.saved && (
        <p role="status">현재 입력을 먼저 저장하세요. 이전 저장본의 계산 결과는 표시하지 않습니다.</p>
      )}
      <div className="payment-calculation-actions">
        <button
          type="button"
          disabled={busy || !observation.ready || !observation.saved || observation.busy}
          onClick={() => void load('ATTRIBUTION_BALANCE')}
        >
          귀속 차액 조회
        </button>
        {canProcess && (
          <button
            type="button"
            disabled={busy || !observation.ready || !observation.saved || observation.busy}
            onClick={() => void load('PAYMENT_CANDIDATE')}
          >
            지급 후보 검토 조회
          </button>
        )}
      </div>
      {busy && <p role="status">서버 계산 결과를 읽고 있어요.</p>}
      {error && <p role="alert">{error}</p>}
      {result && (
        <section aria-label="서버 계산 조회 결과" aria-live="polite">
          <strong>
            {result.status === 'CALCULATED'
              ? '계산 조회 완료'
              : result.status === 'REVIEW_REQUIRED'
                ? '검토 필요'
                : '자료·지원 범위 확인'}
          </strong>
          <p>저장 상태: {result.paymentStatus ?? '미확인'} · 지급 완료액과 구분하세요.</p>
          <dl>
            {Object.entries(result.amounts ?? {}).map(([key, value]) => (
              <div key={key}>
                <dt>{names[key] ?? key}</dt>
                <dd>{won(value)}</dd>
              </div>
            ))}
          </dl>
          {result.capPreview?.map((row, index) => (
            <div className="payment-calculation-cap" key={index}>
              <strong>
                계약 #{String(row.contractId ?? '미확인')} ·{' '}
                {String(row.resultStatusLabel ?? row.resultStatus ?? '미확인')}
              </strong>
              <dl>
                {['limitAmount', 'candidateAmount', 'includedAmount', 'remainingAmount'].map((key) => (
                  <div key={key}>
                    <dt>{names[key]}</dt>
                    <dd>{won(row[key])}</dd>
                  </div>
                ))}
              </dl>
              {typeof row.applicabilityReason === 'string' && <p>{row.applicabilityReason}</p>}
            </div>
          ))}
          {result.confirmable != null && (
            <p>
              계산기의 후보 판정: {result.confirmable ? '차단 사유 없음' : '차단 사유 확인'} · 실제 사전검증 완료 증표가
              아닙니다.
            </p>
          )}
          {result.blockers?.map((item, index) => (
            <p key={index}>
              {item.code} · {item.message}
            </p>
          ))}
          {result.basis?.sourceRefs?.map((source) => (
            <p key={source.sourceId}>
              근거: {source.title} · #{source.entityId}
              {source.version ? ` · 갱신 ${dateTimeSeconds(source.version)}` : ''}
            </p>
          ))}
          <small>행별 원 단위 HALF_UP 후 합산 · 조회 시각 {dateTimeSeconds(result.calculatedAt)} (KST)</small>
          <p>{result.recommendation?.reason}</p>
          <small>
            조회 시점의 자료입니다. 추천값 반영·가이드 통과에 사용할 수 없으며, 진행 전 다시 조회해야 합니다.
          </small>
        </section>
      )}
    </details>
  )
}
