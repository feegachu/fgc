import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useLocation } from 'react-router'
import { Button } from '../../components/Button'
import { CellDisclosure } from '../../components/CellDisclosure'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { EvidenceLink } from '../../components/EvidenceLink'
import { Modal } from '../../components/Modal'
import { StatusBadge } from '../../components/StatusBadge'
import { date, int, rate, rateText } from '../../lib/format'
import { paymentEvidenceText } from '../../lib/screenText'
import { capDetailQuery, exceptionCapBasisQuery } from './api'
import { STAGES } from './labels'
import type { CapBasis, ExceptionCapBasis } from './api'
import { Amount, CapError, CapStatus, CapUsage } from './badges'
import './cap.css'

export interface CapDetailModalProps {
  /** #408 CONT-W02도 사용. null/undefined/빈 문자열이면 닫힘, URL/선택 상태는 호출자가 소유한다. */
  capCheckId: string | number | null | undefined
  onClose: () => void
  exceptionCaseId?: number
}
type Detail = NonNullable<CapBasis['details']>[number]
const classification: Record<string, string> = { INCLUDED: '산입', EXCLUDED: '제외', REVIEW_REQUIRED: '검토필요' }
const columns: TableColumn<Detail>[] = [
  { key: 'seq', label: '순서', width: '4rem', render: (row) => int(row.detailSeq) },
  {
    key: 'item',
    label: '항목',
    width: '10rem',
    render: (row) => <CellDisclosure preview={row.commissionItemName || '-'} full={row.commissionItemName || '-'} />,
  },
  { key: 'amount', label: '금액', width: '8rem', align: 'number', render: (row) => <Amount value={row.amount} /> },
  {
    key: 'paymentStatus', label: '지급 상태', width: '7rem', align: 'center',
    render: (row) => row.paymentStatus ? (
      <StatusBadge tone={row.paymentStatus === 'CONFIRMED' ? 'success' : 'neutral'} tablePresentation="dot">
        {{ CONFIRMED: '확정', DRAFT: '작성중', CANCELLED: '취소' }[row.paymentStatus] ?? '-'}
      </StatusBadge>
    ) : '지급건 없음',
  },
  {
    key: 'classification',
    label: '판정',
    width: '6rem',
    align: 'center',
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
        {classification[row.classificationSnapshot ?? ''] || row.classificationSnapshot || '-'}
      </StatusBadge>
    ),
  },
  {
    key: 'reason',
    label: '판단 이유',
    width: '12rem',
    render: (row) => <CellDisclosure preview={rateText(row.decisionReason)} full={rateText(row.decisionReason)} />,
  },
  {
    key: 'evidence',
    label: '근거',
    width: '11rem',
    render: (row) =>
      row.evidenceRef ? (
        <EvidenceLink label={paymentEvidenceText(row.evidenceRef)}>{paymentEvidenceText(row.evidenceRef)}</EvidenceLink>
      ) : (
        '증빙 없음'
      ),
  },
]

/** CAP-W02: 검증 당시 cap_check/calculation_snapshot/detail을 표시하고 판정을 다시 계산하지 않는다. */
export function CapDetailModal({ capCheckId, onClose, exceptionCaseId }: CapDetailModalProps) {
  const detail = useQuery(capDetailQuery(exceptionCaseId ? null : capCheckId))
  const exceptionDetail = useQuery(exceptionCapBasisQuery(capCheckId ? exceptionCaseId : null))
  const activeQuery = exceptionCaseId ? exceptionDetail : detail
  const location = useLocation()
  const listUrl = new URL(location.pathname === '/exceptions' ? location.pathname + location.search : '/exceptions', 'http://workspace.local')
  listUrl.searchParams.delete('selected')
  const exceptionList = listUrl.pathname + listUrl.search + listUrl.hash
  return (
    <Modal
      open={capCheckId != null && capCheckId !== ''}
      title="계산근거"
      className="cap-react-modal"
      onClose={onClose}
      footer={
        <>
          <Button data-guide-feature="features.cap.cap-detail-modal.button" variant="secondary" onClick={onClose}>
            뒤로가기
          </Button>
          <Link data-guide-feature="features.cap.cap-detail-modal.link" className="button button-primary" to={exceptionList}>
            예외 목록 보기
          </Link>
        </>
      }
    >
      {activeQuery.isError ? (
        <CapError error={activeQuery.error} retry={() => void activeQuery.refetch()} />
      ) : exceptionCaseId && exceptionDetail.isFetching ? (
        <p role="status">처리 후 계산근거를 불러오는 중입니다.</p>
      ) : exceptionCaseId && exceptionDetail.data?.detectedBasis ? (
        <section data-guide-feature="features.cap.cap-detail-modal.exception-section"><ExceptionBasisContent data={exceptionDetail.data} /></section>
      ) : !exceptionCaseId && detail.data?.capCheck ? (
        <section data-guide-feature="features.cap.cap-detail-modal.section">
          <BasisContent data={detail.data} />
        </section>
      ) : (
        <p role="status">계산근거를 불러오는 중입니다.</p>
      )}
    </Modal>
  )
}

function ExceptionBasisContent({ data }: { data: ExceptionCapBasis }) {
  const [detected, setDetected] = useState(false)
  const current = data.currentBasis
  return <>
    {current && <div className="cap-react-basis-switch">
      <Button data-guide-feature="features.cap.cap-detail-modal.current" variant={detected ? 'secondary' : 'primary'} onClick={() => setDetected(false)}>처리 후 보기</Button>
      <Button data-guide-feature="features.cap.cap-detail-modal.detected" variant={detected ? 'primary' : 'secondary'} onClick={() => setDetected(true)}>검출 당시 보기</Button>
    </div>}
    <BasisContent data={detected || !current ? data.detectedBasis! : current} historical={detected || !current} />
    {!detected && !!data.deferredPayments?.length && <section data-guide-feature="features.cap.cap-detail-modal.deferred" className="surface cap-react-detail-lines">
      <h3>이연 지급 내역</h3>
      <DataTable caption="이연 지급 내역" rows={data.deferredPayments} rowKey={row => String(row.commissionTransactionId)} columns={[
        { key: 'item', label: '항목', render: row => row.commissionItemName ?? '-' },
        { key: 'amount', label: '이연 금액', align: 'number', render: row => <Amount value={row.amount} /> },
        { key: 'date', label: '지급 예정일', render: row => date(row.scheduledPaymentDate) },
        { key: 'attribution', label: '귀속일', render: row => date(row.attributions?.[0]?.attributionDate) },
        { key: 'status', label: '지급 상태', render: row => <StatusBadge tone={row.status === 'CONFIRMED' ? 'success' : 'neutral'} tablePresentation="dot">{{ DRAFT: '작성중', CONFIRMED: '확정', CANCELLED: '취소' }[row.status ?? 'DRAFT']}</StatusBadge> },
      ]} />
    </section>}
  </>
}

function BasisContent({ data, historical = false }: { data: CapBasis; historical?: boolean }) {
  const item = data.capCheck!
  const snapshot = data.calculationSnapshot ?? {}
  if (item.resultStatus === 'NOT_APPLICABLE') {
    const decision = snapshot.applicability as Record<string, unknown> | undefined
    return (
      <div className="surface">
        <h3>
          {item.contractNo || '-'}, {item.paymentStageLabel || '-'}
        </h3>
        <CapStatus code={item.resultStatus} label={item.resultStatusLabel} />
        <p>{rateText(decision?.reason ?? snapshot.notApplicableReason ?? '적용대상 아님')}</p>
        <dl>
          <div>
            <dt>계약일</dt>
            <dd>{String(decision?.contractDate ?? '-')}</dd>
          </div>
          <div>
            <dt>적용 시작 계약일</dt>
            <dd>{String(decision?.effectiveContractDate ?? '-')}</dd>
          </div>
        </dl>
        <p>한도/산입금액/사용률 계산 대상이 아닙니다.</p>
      </div>
    )
  }
  const details = data.details ?? []
  const displayedColumns = historical ? columns.map(column => column.key !== 'paymentStatus' ? column : {
    ...column, label: '검출 당시 지급 상태', width: '10rem',
    render: (row: Detail) => row.paymentStatusSnapshot
      ? <StatusBadge tone={row.paymentStatusSnapshot === 'CONFIRMED' ? 'success' : 'neutral'} tablePresentation="dot">{{ CONFIRMED: '확정', DRAFT: '작성중', CANCELLED: '취소' }[row.paymentStatusSnapshot] ?? '-'}</StatusBadge>
      : '상태 기록 없음',
  }) : columns
  return (
    <div className="cap-react-detail">
      <header className="cap-react-detail-summary">
        <div>
          <strong>{item.contractNo || '-'}</strong>
          <span className="cap-react-detail-stage">{item.paymentStageLabel || STAGES[item.paymentStage as keyof typeof STAGES] || '-'}</span>
          <p>기준일 {date(item.asOfDate)}</p>
        </div>
        <aside className="cap-react-detail-limit" aria-label="1,200% 한도 요약">
          <div className="cap-react-detail-limit-heading"><h3>1,200% 한도</h3><CapStatus code={item.resultStatus} label={item.resultStatusLabel} /></div>
          <CapUsage value={item.usagePct} status={item.resultStatus} warning={data.warningStartUsagePct} />
          {snapshot.warningUsagePct != null && <p>주의 기준 {rate(snapshot.warningUsagePct)}%</p>}
          <dl>
            <div><dt>산입 합계</dt><dd><Amount value={item.includedAmount} /></dd></div>
            <div><dt>한도</dt><dd><Amount value={item.limitAmount} /></dd></div>
            <div><dt>{(item.remainingAmount ?? 0) < 0 ? '초과액' : '잔여 한도'}</dt><dd className={(item.remainingAmount ?? 0) < 0 ? 'cap-react-detail-excess' : undefined}><Amount value={item.remainingAmount == null ? undefined : Math.abs(item.remainingAmount)} /></dd></div>
          </dl>
        </aside>
      </header>
        <section data-guide-feature="features.cap.cap-detail-modal.section-5" className="surface cap-react-detail-lines">
          <h3>
            항목별 산입 내역 <small>{int(details.length)}개 항목</small>
          </h3>
          <DataTable
            caption="항목별 산입 내역"
            columns={displayedColumns}
            rows={details}
            rowKey={(row) => String(row.detailSeq)}
            emptyMessage="저장된 항목별 산입 내역이 없습니다."
            footer={
              <tr>
                <th scope="row" colSpan={2}>산입 합계</th>
                <td className="is-number"><Amount value={item.includedAmount} /></td>
                <td colSpan={4}>
                  <div className="cap-react-detail-totals">
                    <span>한도 <Amount value={item.limitAmount} /></span>
                    <span className={(item.remainingAmount ?? 0) < 0 ? 'cap-react-detail-excess' : undefined}>
                      {(item.remainingAmount ?? 0) < 0 ? '초과액' : '잔여 한도'} <Amount value={item.remainingAmount == null ? undefined : Math.abs(item.remainingAmount)} />
                    </span>
                  </div>
                </td>
              </tr>
            }
          />
        </section>
    </div>
  )
}
