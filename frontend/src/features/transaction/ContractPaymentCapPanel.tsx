import { StatusBadge, type StatusTone } from '../../components/StatusBadge'
import { rateText, usageRate, won } from '../../lib/format'
import type { CapPreview, Precheck } from './form-api'

type Row = { id: number; name: string; item: string; amount: number; result?: Precheck }
const tones: Record<string, StatusTone> = {
  NORMAL: 'success', WARNING: 'warning', VIOLATION: 'error', REVIEW_REQUIRED: 'review', NOT_APPLICABLE: 'neutral',
}
// A preview is a contract/stage total, not an amount to add across payment rows.
const basis = (p: CapPreview) => JSON.stringify([
  p.contractId, p.paymentStage, p.asOfDate, p.capRuleSetId, p.basePremiumAmount,
  p.includedAmount, p.limitAmount, p.remainingAmount, p.usagePct, p.resultStatus,
])

export function ContractPaymentCapPanel({ rows, pending }: { rows: Row[]; pending: boolean }) {
  const checked = rows.filter(row => row.result).length
  const previews = rows.flatMap(row => row.result?.capPreview ?? [])
  const first = previews[0]
  const common = !pending && checked === rows.length && previews.length === rows.length
    && first && previews.every(p => basis(p) === basis(first)) ? first : undefined
  const total = rows.reduce((sum, row) => sum + row.amount, 0)
  return <section className="surface transaction-cap-panel contract-batch-cap" id="cap-panel" aria-labelledby="batch-cap-title">
    <header className="surface-header transaction-surface-header">
      <h2 className="surface-title" id="batch-cap-title">1,200% 일괄 사전검증</h2>
      <span className="transaction-panel-meta">{pending ? '검증 중' : `${checked}/${rows.length}행 검증`}</span>
    </header>
    <div className="surface-body" aria-busy={pending}>
      <div className="contract-batch-cap-summary">
        <span>선택 지급액</span><strong>{Number.isSafeInteger(total) ? won(total) : '-'}</strong>
      </div>
      {common && common.resultStatus !== 'NOT_APPLICABLE' && <div className="contract-batch-cap-summary-grid">
        <div><span>계약 한도</span><strong>{won(common.limitAmount)}</strong></div>
        <div><span>검증 기준 산입 총액</span><strong>{won(common.includedAmount)}</strong></div>
        <div><span>잔여 한도</span><strong>{won(common.remainingAmount)}</strong></div>
        <div><span>한도 사용률</span><strong>{usageRate(common.usagePct)}%</strong></div>
      </div>}
      {rows.length ? <div className="contract-batch-cap-table">
        <table>
          <thead><tr><th>설계사 / 수수료 항목</th><th>지급액</th><th>산입액 / 판정</th></tr></thead>
          <tbody>{rows.map(row => <tr key={row.id}>
            <td><strong>{row.name}</strong><span>{row.item}</span></td>
            <td>{won(row.amount)}</td>
            <td>{!row.result ? <span>검증 대기</span> : <>
              {row.result.capPreview?.map((preview, index) => <div key={index} className="contract-batch-cap-result">
                <span>{won(preview.candidateAmount)}</span>
                <StatusBadge tone={tones[preview.resultStatus ?? 'REVIEW_REQUIRED']}>
                  {preview.resultStatusLabel ?? '확인 필요'}
                </StatusBadge>
                {!common && preview.resultStatus !== 'NOT_APPLICABLE' && <small>
                  한도 {won(preview.limitAmount)} · 사용률 {usageRate(preview.usagePct)}%
                </small>}
                {preview.applicabilityReason && <small>{rateText(preview.applicabilityReason)}</small>}
              </div>)}
              {row.result.capExclusions?.map((excluded, index) => <div key={`excluded-${index}`}>
                <StatusBadge tone="neutral">산입 제외</StatusBadge>
                <small>{rateText(excluded.reason ?? '적용 대상 아님')}</small>
              </div>)}
              {!row.result.capPreview?.length && !row.result.capExclusions?.length && <span>판정 없음</span>}
              {!!row.result.blockers?.length && <small className="field-error">
                {row.result.blockers.map(blocker => rateText(blocker.message ?? '확인 필요')).join(' / ')}
              </small>}
            </>}</td>
          </tr>)}</tbody>
        </table>
      </div> : <p className="transaction-panel-meta">계약과 회차를 선택해 지급 건을 확인하세요.</p>}
    </div>
  </section>
}
