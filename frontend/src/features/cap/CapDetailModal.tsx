import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { Button } from '../../components/Button'
import { CellDisclosure } from '../../components/CellDisclosure'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { EvidenceLink } from '../../components/EvidenceLink'
import { Modal } from '../../components/Modal'
import { StatusBadge } from '../../components/StatusBadge'
import { date, int, rate, usageRate, won } from '../../lib/format'
import { capDetailQuery } from './api'
import { STAGES } from './labels'
import type { CapBasis } from './api'
import { Amount, CapError, CapEvidence, CapStatus, CapUsage } from './badges'
import './cap.css'

export interface CapDetailModalProps {
  /** #408 CONT-W02도 사용. null이면 닫힘, URL/선택 상태는 호출자가 소유한다. */
  capCheckId: string | number | null
  onClose: () => void
}
type Detail = NonNullable<CapBasis['details']>[number]
const classification: Record<string, string> = { INCLUDED: '산입', EXCLUDED: '제외', REVIEW_REQUIRED: '검토필요' }
const columns: TableColumn<Detail>[] = [
  { key: 'seq', label: '순서', width: '4rem', render: (row) => int(row.detailSeq) },
  {
    key: 'item',
    label: '항목',
    width: '10rem',
    render: (row) => <CellDisclosure preview={row.commissionItemName || '—'} full={row.commissionItemName || '—'} />,
  },
  { key: 'amount', label: '금액', width: '8rem', align: 'number', render: (row) => <Amount value={row.amount} /> },
  {
    key: 'classification',
    label: '판정',
    width: '6rem',
    render: (row) => (
      <StatusBadge
        tone={
          row.classificationSnapshot === 'INCLUDED'
            ? 'info'
            : row.classificationSnapshot === 'REVIEW_REQUIRED'
              ? 'review'
              : 'neutral'
        }
      >
        {classification[row.classificationSnapshot ?? ''] || row.classificationSnapshot || '—'}
      </StatusBadge>
    ),
  },
  {
    key: 'reason',
    label: '판단 이유',
    width: '12rem',
    render: (row) => <CellDisclosure preview={row.decisionReason || '—'} full={row.decisionReason || '—'} />,
  },
  {
    key: 'evidence',
    label: '근거',
    width: '11rem',
    render: (row) =>
      row.evidenceRef ? (
        <EvidenceLink label={row.evidenceRef}>{row.evidenceRef}</EvidenceLink>
      ) : (
        '증빙 미연결 / 후속 연결 대기'
      ),
  },
]

/** CAP-W02: 검증 당시 cap_check/calculation_snapshot/detail을 표시하고 판정을 다시 계산하지 않는다. */
export function CapDetailModal({ capCheckId, onClose }: CapDetailModalProps) {
  const detail = useQuery(capDetailQuery(capCheckId))
  return (
    <Modal
      open={capCheckId !== null}
      title="계산근거"
      className="cap-react-modal"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            닫기
          </Button>
          <Link className="button button-primary" to="/exceptions" onClick={onClose}>
            예외 목록 보기
          </Link>
        </>
      }
    >
      {detail.isError ? (
        <CapError error={detail.error} retry={() => void detail.refetch()} />
      ) : detail.data?.capCheck ? (
        <BasisContent data={detail.data} />
      ) : (
        <p role="status">계산근거를 불러오는 중입니다.</p>
      )}
    </Modal>
  )
}

function BasisContent({ data }: { data: CapBasis }) {
  const item = data.capCheck!
  const snapshot = data.calculationSnapshot ?? {}
  const details = data.details ?? []
  const insurerStage = item.paymentStage === 'INSURER_TO_GA'
  const multiplier =
    typeof snapshot.premiumMultiplier === 'string' || typeof snapshot.premiumMultiplier === 'number'
      ? String(snapshot.premiumMultiplier)
      : '—'
  // 수식 문자열만 구성한다. 한도/사용률/판정은 재계산하지 않는다(#468).
  const limitParts = [int(item.basePremiumAmount), '×', multiplier]
  if (item.refund12mAmount != null && item.refund12mAmount !== 0) limitParts.push('−', int(item.refund12mAmount))
  if (insurerStage && item.complianceDeductionAmount != null && item.complianceDeductionAmount !== 0)
    limitParts.push('+', int(item.complianceDeductionAmount))
  // 1차와 동일한 상세 합계 검산. 서버 산입금액을 덮어쓰거나 판정에 사용하지 않는다.
  const includedTotal = details.reduce(
    (sum, line) => (line.classificationSnapshot === 'INCLUDED' ? sum + (line.amount ?? 0) : sum),
    0,
  )
  const matches = includedTotal === item.includedAmount
  const inputs = [
    ['월납환산 초회보험료', won(item.basePremiumAmount)],
    ['한도 배수', `${multiplier}배`],
    ['해약환급금 합산', won(item.refund12mAmount)],
    ['준법경영비 공제', insurerStage ? won(item.complianceDeductionAmount) : '적용하지 않음'],
    ['적용 룰셋', item.capRuleSetId == null ? '—' : `룰셋 ID ${item.capRuleSetId}`],
  ]
  return (
    <div className="cap-react-detail">
      <header className="cap-react-detail-summary">
        <strong>{item.contractNo || '—'}</strong>
        <span>{item.paymentStageLabel || STAGES[item.paymentStage as keyof typeof STAGES] || '—'}</span>
        <CapStatus code={item.resultStatus} label={item.resultStatusLabel} />
        <p>
          기준일 {date(item.asOfDate)} · 룰셋 ID {item.capRuleSetId ?? '—'} · cap_check #{item.capCheckId ?? '—'}
        </p>
      </header>
      <div className="cap-react-detail-grid">
        <div className="cap-react-detail-left">
          <section className="surface">
            <h3>① 입력값</h3>
            <table className="data-table">
              <tbody>
                {inputs.map(([label, value]) => (
                  <tr key={label}>
                    <th scope="row">{label}</th>
                    <td>{value}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
          <section className="surface">
            <h3>
              ② 계산식 <CapEvidence />
            </h3>
            <p>
              한도 = {limitParts.join(' ')} = {won(item.limitAmount)}
            </p>
            <p>
              사용률 = {int(item.includedAmount)} ÷ {int(item.limitAmount)} × 100
            </p>
            <p>= {usageRate(item.usagePct)}%</p>
          </section>
          <section className="surface">
            <h3>④ 합계와 판정</h3>
            <CapUsage value={item.usagePct} status={item.resultStatus} warning={snapshot.warningUsagePct} />
            {snapshot.warningUsagePct != null && <p>주의 기준 {rate(snapshot.warningUsagePct)}%</p>}
            <dl>
              <div>
                <dt>산입 합계</dt>
                <dd>
                  <Amount value={item.includedAmount} />
                </dd>
              </div>
              <div>
                <dt>한도</dt>
                <dd>
                  <Amount value={item.limitAmount} />
                </dd>
              </div>
              <div>
                <dt>잔여</dt>
                <dd>
                  <Amount value={item.remainingAmount} />
                </dd>
              </div>
            </dl>
            <CapStatus code={item.resultStatus} label={item.resultStatusLabel} />
          </section>
        </div>
        <section className="surface cap-react-detail-lines">
          <h3>
            ③ 항목별 산입 내역 <small>{int(details.length)}개 항목</small>
          </h3>
          <DataTable
            caption="항목별 산입 내역"
            columns={columns}
            rows={details}
            rowKey={(row) => String(row.detailSeq)}
            emptyMessage="저장된 항목별 산입 내역이 없습니다."
          />
          <p>
            산입 합계 {won(includedTotal)} ·{' '}
            <span className={matches ? undefined : 'is-negative-amount'}>
              {matches ? '제외 항목 미포함' : '저장 산입금액과 항목별 산입 합계가 일치하지 않습니다.'}
            </span>
          </p>
        </section>
      </div>
    </div>
  )
}
