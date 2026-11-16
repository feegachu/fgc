import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Button } from '../../components/Button'
import { Modal } from '../../components/Modal'
import { KpiCard } from '../../components/KpiCard'
import { date } from '../../lib/format'
import { ScreenHelp, type ScreenHelpStep } from '../assistant/guide/ScreenHelp'
import { contractResultsQuery, detailQuery, resultBasisQuery, type Detail, type ResultBasis } from '../reconciliation/api'
import { amountText } from '../reconciliation/amounts'
import { Failure, ResultBadge } from '../reconciliation/display'
import { reconciliationExplanation } from '../reconciliation/explanation'
import { PAYMENT_STAGE } from '../reconciliation/labels'
import { contractStateLabel, useExceptionContract } from './ContractStatusContext'
import { ContractStatusBadge } from '../contract/badges'

const walkthrough: ScreenHelpStep[] = [
  { targetId: 'exception.reco.cause', group: '계약 정보', title: '계약 정보와 상태', instruction: '계약번호, 대사 결과 유형과 현재 계약 상태를 확인하세요.', details: [], interaction: '내용을 읽고 다음으로 진행하세요.' },
  { targetId: 'exception.reco.amounts', group: '금액 비교', title: '예상액과 지급 자료 비교', instruction: '예상 지급액, 실제 지급 자료, 차액을 비교하세요.', details: [], interaction: '내용을 읽고 다음으로 진행하세요.' },
  { targetId: 'exception.reco.results', group: '계약 대사 결과', title: '관련 항목 확인', instruction: '같은 실행에서 해당 계약의 모든 대사 항목을 확인하세요. 원인 보기를 누르면 이 창에서 항목이 바뀝니다.', details: [], interaction: '뒤로가기를 누르면 예외 상세로 돌아갑니다.' },
]

export function ExceptionReconciliationModal({ resultId, onClose }: { resultId: string; onClose: () => void }) {
  const basis = useQuery(resultBasisQuery(resultId))
  return <Modal open title="계약 대사 결과" className="modal-large exception-reconciliation-modal kpi-summary-scope" onClose={onClose}
    headerActions={<ScreenHelp screenId="RECO-W02" variant="detail" walkthrough={walkthrough} />}
    footer={<Button data-guide-feature="features.exception.reconciliation-back" variant="secondary" onClick={onClose}>뒤로가기</Button>}>
    <Failure error={basis.error} />
    {basis.isPending ? <p role="status">대사 결과를 불러오는 중입니다.</p> : basis.data && <BasisContent key={`${resultId}:${basis.data.currentRunId ?? 'detected'}`} basis={basis.data} />}
  </Modal>
}

function BasisContent({ basis }: { basis: ResultBasis }) {
  const [detected, setDetected] = useState(false)
  const [selectedId, setSelectedId] = useState('')
  const selected = useQuery(detailQuery(selectedId))
  const hasRevision = basis.currentRunId != null
  const current = !detected && hasRevision
  const initial = current ? basis.currentResults?.[0] : basis.detected
  const detail = selectedId ? selected.data : initial
  const removed = current && !basis.currentResults?.length && !selectedId
  const switchBasis = (value: boolean) => { setDetected(value); setSelectedId('') }
  return <>
    {hasRevision && <div className="exception-reco-basis-controls" role="group" aria-label="대사 조회 기준">
      <Button data-guide-feature="features.exception.reconciliation-current" variant="secondary" aria-pressed={!detected} onClick={() => switchBasis(false)}>처리 후 대사</Button>
      <Button data-guide-feature="features.exception.reconciliation-detected" variant="secondary" aria-pressed={detected} onClick={() => switchBasis(true)}>검출 당시 대사</Button>
      <span className="text-secondary">{detected ? '처리 전 원본 기록입니다.' : '정정과 재대사가 반영된 결과입니다.'}</span>
    </div>}
    {!hasRevision && <p className="text-secondary">검출 당시 대사 결과입니다. 정정 후 재대사 결과가 생성되면 이 화면에 반영됩니다.</p>}
    <Failure error={selected.error} />
    {selectedId && selected.isPending ? <p role="status">선택한 항목을 불러오는 중입니다.</p> : (detail || removed) && <ReconciliationContent
      detail={detail ?? basis.detected!} runId={current ? basis.currentRunId! : basis.detected?.reconciliationRunId}
      removed={removed} onSelect={setSelectedId} />}
  </>
}

function ReconciliationContent({ detail, runId, removed, onSelect }: { detail: Detail; runId?: number; removed: boolean; onSelect: (id: string) => void }) {
  const contract = useExceptionContract(detail.contractId)
  const related = useQuery(contractResultsQuery(String(runId ?? detail.reconciliationRunId ?? ''), String(detail.contractId ?? '')))
  const snapshot = detail.detailSnapshot
  const stage = snapshot && typeof snapshot === 'object' && 'paymentStage' in snapshot ? String(snapshot.paymentStage) : ''
  const dueDates = [...new Set((detail.matches ?? []).map(match => match.dueDate).filter(Boolean))]
  const explanation = removed ? '처리 후 대사에는 이 지급 항목이 없습니다.\n지급 취소나 비교 대상 변경 여부를 처리 이력과 함께 확인하세요. 검출 당시 기록은 위 버튼에서 확인할 수 있습니다.' : reconciliationExplanation(detail)
  return <div className="exception-reconciliation-content">
    <section data-guide-feature="features.exception.reconciliation-cause" data-guide-target="exception.reco.cause" className="exception-reco-cause">
      <div className="exception-reco-summary">
      <div className="exception-reco-heading"><h3>{detail.contractNo || '계약 정보 확인 필요'}</h3>{!removed && <ResultBadge type={detail.resultType} label={detail.resultTypeLabel} />}</div>
      <p className="text-secondary">{PAYMENT_STAGE[stage as keyof typeof PAYMENT_STAGE] || '지급단계 확인 필요'} · {detail.commissionItemName || '-'}{detail.installmentNo != null ? ` · ${detail.installmentNo}회차` : ''}</p>
      {explanation && <p className="exception-reco-explanation">{explanation}</p>}
      {dueDates.length > 0 && <p className="text-secondary">지급 기준일 {dueDates.map(value => date(value)).join(', ')}</p>}
      {!removed && <p className="text-secondary">대사 기준 기록일 {date(detail.createdAt)}</p>}
      </div>
      <div className="exception-reco-current-status"><span>현재 계약 상태</span><ContractStatusBadge code={contract.data?.contractStatus} label={contractStateLabel(contract.data?.contractStatus, contract.data?.contractStatusLabel)} /></div>
    </section>
    <div data-guide-feature="features.exception.reconciliation-amounts" data-guide-target="exception.reco.amounts" className="exception-reco-amounts kpi-group">
      {/* 미리 서식화한 문자열로 표시하여 공통 KPI의 숫자 애니메이션을 사용하지 않는다. */}
      <KpiCard guideId="features.exception.reconciliation-expected-kpi" label="예상 지급액" value={removed ? '비교 항목 없음' : amountText(detail.expectedTotalAmount, false)} unit={removed ? undefined : '원'} zero={!removed && Number(detail.expectedTotalAmount) === 0} />
      <KpiCard guideId="features.exception.reconciliation-actual-kpi" label="실제 지급 자료" value={removed ? '비교 항목 없음' : amountText(detail.actualTotalAmount, false)} unit={removed ? undefined : '원'} zero={!removed && Number(detail.actualTotalAmount) === 0} />
      <KpiCard guideId="features.exception.reconciliation-difference-kpi" label="차액 (실제 − 예상)" value={removed ? '-' : amountText(detail.differenceAmount, false)} unit={removed ? undefined : '원'} tone={!removed && Number(detail.differenceAmount) !== 0 ? 'error' : 'neutral'} zero={!removed && Number(detail.differenceAmount) === 0} />
    </div>
    <section data-guide-feature="features.exception.reconciliation-results" data-guide-target="exception.reco.results" className="surface">
      <div className="surface-header"><h3 className="surface-title">해당 계약의 대사 결과</h3><span className="text-secondary">같은 대사 실행 기준</span></div>
      <Failure error={related.error} />
      {related.isPending ? <p role="status">관련 항목을 불러오는 중입니다.</p> : related.data && <div className="exception-reco-table-scroll"><table data-guide-feature="features.exception.reconciliation-table" aria-label="해당 계약의 대사 결과" className="exception-reco-table">
        <thead><tr><th scope="col">수수료 항목</th><th scope="col">회차</th><th scope="col">예상 지급액</th><th scope="col">실제 지급 자료</th><th scope="col">차액</th><th scope="col">대사 결과</th><th scope="col">상세</th></tr></thead>
        <tbody>{related.data.map(row => <tr key={row.reconciliationResultId} className={row.reconciliationResultId === detail.reconciliationResultId ? 'is-selected' : ''}>
          <td>{row.commissionItemName || '-'}</td><td>{row.installmentNo ?? '-'}</td>
          <td>{amountText(row.expectedTotalAmount)}</td><td>{amountText(row.actualTotalAmount)}</td><td className={Number(row.differenceAmount) !== 0 ? 'exception-reco-difference' : ''}>{amountText(row.differenceAmount)}</td>
          <td><ResultBadge type={row.resultType} label={row.resultTypeLabel} /></td>
          <td><Button data-guide-feature="features.exception.reconciliation-select" variant="secondary" aria-label={`${row.commissionItemName || '항목'} ${row.installmentNo != null ? `${row.installmentNo}회차 ` : ''}원인 보기`} disabled={row.reconciliationResultId == null || row.reconciliationResultId === detail.reconciliationResultId} onClick={() => onSelect(String(row.reconciliationResultId))}>{row.reconciliationResultId === detail.reconciliationResultId ? '보는 중' : '원인 보기'}</Button></td>
        </tr>)}</tbody>
      </table>{related.data.length === 0 && <p className="text-secondary">관련 대사 결과가 없습니다.</p>}</div>}
    </section>
  </div>
}
