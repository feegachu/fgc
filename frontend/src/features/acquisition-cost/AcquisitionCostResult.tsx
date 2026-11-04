import { Button } from '../../components/Button'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { StatusBadge } from '../../components/StatusBadge'
import type { StatusTone } from '../../components/StatusBadge'
import { ApiError } from '../../lib/api/errors'
import { date, dateTime, int, usageRate, won } from '../../lib/format'
import { contractErrorText } from '../contract/detailState'
import type { AcquisitionCheck, AcquisitionItem } from './api'
import { amountText, percentText, policyText } from './labels'
import './acquisition-cost.css'

const tones: Record<string, StatusTone> = {
  NORMAL: 'success',
  VIOLATION: 'risk',
  REVIEW_REQUIRED: 'review',
  NOT_APPLICABLE: 'neutral',
}
export function AcquisitionStatus({ status, label }: { status?: string; label?: string }) {
  return (
    <StatusBadge optionCount={4} tone={tones[status ?? ''] ?? 'neutral'}>
      {label ?? '-'}
    </StatusBadge>
  )
}
const refsText = (policy?: AcquisitionCheck['limitPolicy']) =>
  [...(policy?.regulationRefs ?? []), ...(policy?.sourceRefs ?? [])].join(', ') || '-'

function LimitResult({ item, indirect }: { item: AcquisitionItem; indirect?: { amount?: number | null } }) {
  const rows: [string, string][] = [
    ['기준금액(계약체결비용)', amountText(item.baseAmount)],
    ['적용률', percentText(item.limitPct)],
    ['한도', amountText(item.limitAmount)],
    ['계산액', amountText(item.calculatedAmount)],
    ...(indirect ? ([['포함된 간접지원경비', amountText(indirect.amount)]] as [string, string][]) : []),
    ['사용률', item.usagePct == null ? '자료 없음' : `${usageRate(item.usagePct)}%`],
  ]
  return (
    <article className="acq-limit" aria-label={`${item.label} 한도 결과`}>
      <header>
        <h3>{item.label} 한도</h3>
        <AcquisitionStatus status={item.status} label={item.statusLabel} />
      </header>
      <dl>
        {rows.map(([label, value]) => (
          <div key={label}>
            <dt>{label}</dt>
            <dd className="tabular-nums">{value}</dd>
          </div>
        ))}
      </dl>
      <p>{item.reason}</p>
    </article>
  )
}

type LineGroup = NonNullable<AcquisitionCheck['lineGroups']>[number]
// 분할 화면의 좁은 상세에서도 금액 칸이 보이게 항목과 회차를 한 칸에 둔다.
const groupColumns: TableColumn<LineGroup>[] = [
  { key: 'bucket', label: '산입 위치', width: '6.5rem', render: (row) => row.bucketLabel },
  {
    key: 'item',
    label: '수수료 항목과 회차',
    width: '9rem',
    render: (row) => (
      <>
        {row.itemName}
        <span className="acq-cell-sub">
          {row.installmentFrom === row.installmentTo ? row.installmentFrom : `${row.installmentFrom}~${row.installmentTo}`}회차,{' '}
          {int(row.lineCount)}건
        </span>
      </>
    ),
  },
  { key: 'amount', label: '금액', width: '6.5rem', align: 'number', render: (row) => won(row.amount) },
]
type ExclusionLine = NonNullable<AcquisitionCheck['exclusions']>[number]
const exclusionColumns: TableColumn<ExclusionLine>[] = [
  {
    key: 'item',
    label: '항목과 회차',
    width: '6.5rem',
    render: (row) => (
      <>
        {row.itemName}
        <span className="acq-cell-sub">{row.installmentNo}회차</span>
      </>
    ),
  },
  { key: 'amount', label: '금액', width: '6.5rem', align: 'number', render: (row) => won(row.amount) },
  { key: 'decision', label: '계산 반영', width: '5rem', render: (row) => (row.excluded ? '제외' : '포함') },
  { key: 'evidence', label: '증빙', width: '5rem', render: (row) => row.evidenceRef ?? '증빙 없음' },
]

export function AcquisitionCostResult({ check }: { check: AcquisitionCheck }) {
  const basis = check.acquisitionCost
  const reasons = check.reasons ?? []
  return (
    <div className="acq-result">
      <div className="acq-result-head">
        <AcquisitionStatus status={check.resultStatus} label={check.resultStatusLabel} />
        <span>
          {check.contractNo}, {check.paymentStageLabel}, {dateTime(check.checkedAt)} 검증
        </span>
      </div>
      <dl className="acq-facts">
        <div>
          <dt>상품</dt>
          <dd>
            {check.productName}, {check.offeringVersion}
          </dd>
        </div>
        <div>
          <dt>판매개시일</dt>
          <dd>{date(check.salesStartDate)}</dd>
        </div>
        <div>
          <dt>기초서류</dt>
          <dd>
            {check.basicDocumentVersion}, {date(check.basicDocumentDate)}
          </dd>
        </div>
        <div>
          <dt>계약일</dt>
          <dd>{date(check.contractDate)}</dd>
        </div>
      </dl>
      {reasons.length > 0 && (
        <ul className="acq-reasons" aria-label="판정 사유">
          {reasons.map((reason) => (
            <li key={`${reason.code}-${reason.message}`}>{reason.message}</li>
          ))}
        </ul>
      )}
      {check.upfront && check.maintenance && (
        <>
          <div className="acq-limits">
            <LimitResult item={check.upfront} />
            <LimitResult item={check.maintenance} indirect={{ amount: check.indirectSupportAmount }} />
          </div>
          <p className="field-helper">
            두 한도는 같은 기준금액과 각각 비교하며 서로 차감하지 않습니다. 월 상한과 유지 자격은 이 검증에 들어 있지 않습니다.
          </p>
          <p className="field-helper">
            이 결과는 회차 확정이나 지급 가능을 뜻하지 않습니다. 4년·7년 회차는 결과와 관계없이 확정할 수 없습니다.
          </p>
        </>
      )}
      {check.upfront && (
        <section data-guide-feature="features.acquisition-cost.acquisition-cost-result.section" className="acq-basis" aria-label="계산 근거">
          <h3>계산 근거</h3>
          <dl className="acq-facts">
            <div>
              <dt>월납환산 초회보험료</dt>
              <dd className="tabular-nums">{amountText(basis?.premiumAmount)}</dd>
            </div>
            <div>
              <dt>기준율</dt>
              <dd className="tabular-nums">{percentText(basis?.ratePct)}</dd>
            </div>
            <div>
              <dt>기준율 출처</dt>
              <dd>{basis?.description ?? '자료 없음'}</dd>
            </div>
            <div>
              <dt>기준율 정책</dt>
              <dd>
                {policyText(check.paymentPolicy)}
                {check.paymentPolicy ? `, ${check.paymentPolicy.sourceClassLabel}` : ''}
              </dd>
            </div>
            <div>
              <dt>한도율 정책</dt>
              <dd>
                {policyText(check.limitPolicy)}
                {check.limitPolicy ? `, ${check.limitPolicy.sourceClassLabel}` : ''}
              </dd>
            </div>
            <div>
              <dt>정책 근거</dt>
              <dd>{refsText(check.limitPolicy)}</dd>
            </div>
            <div>
              <dt>예정 스케줄</dt>
              <dd>{check.scheduleHeaderId ? `#${check.scheduleHeaderId} v${check.scheduleVersionNo}` : '자료 없음'}</dd>
            </div>
            {check.unclassifiedAmount != null && check.unclassifiedAmount > 0 && (
              <div>
                <dt>판정에 넣지 않은 금액</dt>
                <dd className="tabular-nums">{won(check.unclassifiedAmount)}</dd>
              </div>
            )}
          </dl>
          <p className="field-helper">
            기준금액은 규정이 정한 고정값이 아니라 지급 정책에 등록한 기준율로 계산합니다.
          </p>
          {(check.lineGroups?.length ?? 0) > 0 && (
            <DataTable
              caption="예정 회차 산입 내역"
              columns={groupColumns}
              rows={check.lineGroups ?? []}
              rowKey={(row) => `${row.bucket}-${row.itemCode}-${row.feeComponentType}`}
            />
          )}
          {(check.exclusions?.length ?? 0) > 0 && (
            <DataTable
              caption="제4-32조제7항 제외 후보"
              columns={exclusionColumns}
              rows={check.exclusions ?? []}
              rowKey={(row) => String(row.scheduleLineId)}
            />
          )}
        </section>
      )}
    </div>
  )
}

/** 실패를 0건이나 0원으로 보이지 않는다. 403은 다른 결과를 보이지 않고, 409는 입력을 지우지 않고 다시 조회하게 한다. */
export function AcquisitionFailure({ error, retry }: { error: Error; retry?: () => void }) {
  const status = error instanceof ApiError ? error.status : undefined
  const message =
    status === 403
      ? '이 작업을 할 권한이 없습니다.'
      : status === 409
        ? `최신 상태와 달라졌습니다. ${contractErrorText(error)}`
        : contractErrorText(error)
  return (
    <div className="acq-failure" role="alert">
      <p>{message}</p>
      {status === 422 && error instanceof ApiError && error.field && <p>확인할 항목: {error.field}</p>}
      {retry && status !== 403 && (
        <Button data-guide-feature="features.acquisition-cost.acquisition-cost-result.button" variant="secondary" onClick={retry}>
          {status === 409 ? '다시 조회' : '다시 시도'}
        </Button>
      )}
    </div>
  )
}
