import { KpiCardContent } from '../../components/KpiCard'
import type { ValidationRunDetail } from './api'

type ResultKpi = { label: string; value?: number; step: number; footer?: string; error?: boolean }

function ResultKpis({ cards, completed }: { cards: ResultKpi[]; completed: number }) {
  return <div className="kpi-grid kpi-group vrun-dashboard-kpis">{cards.map((card, index) => {
    const ready = completed >= card.step && card.value != null
    return <div
      key={`${card.label}-${ready}`}
      className={`kpi-card${card.error ? ' kpi-card-error' : ''}${ready ? ' vrun-result-reveal' : ''}`}
      style={{ animationDelay: `${index * 70}ms` }}
    >
      <KpiCardContent label={card.label} value={ready ? card.value : '—'} unit="건"
        footer={ready ? card.footer : completed >= card.step ? '결과 불러오는 중' : '검증 대기'} />
    </div>
  })}</div>
}

export function RunSummaryCards({ detail, completed }: { detail: ValidationRunDetail; completed: number }) {
  const { capSummary: cap, arbitrageSummary: arb, ledgerSummary: ledger, reconciliationSummary: reco, exceptionSummary: exceptions } = detail
  return <ResultKpis completed={completed} cards={[
    { step: 4, label: '1,200% 위반', value: cap?.violationCount, footer: '확정 위반 판정', error: true },
    { step: 4, label: '1,200% 주의', value: cap?.warningCount, footer: '사용률 경고 기준 이상' },
    { step: 5, label: '차익거래 검토대상', value: arb?.candidateCount, footer: '위반 확정 아님' },
    { step: 6, label: '원장 불균형', value: ledger?.imbalanceCount, footer: '이번 실행 · 기표 전 0건 필수', error: true },
    { step: 7, label: '대사 불일치', value: reco?.mismatchCount, footer: '허용오차 0원' },
    { step: 8, label: '미처리 예외', value: exceptions?.openWorkItemCount, footer: '해당 검증월 · 신규 + 검토중' },
  ]} />
}

export function RunExceptionCards({ detail, completed }: { detail: ValidationRunDetail; completed: number }) {
  const summary = detail.exceptionSummary
  return <ResultKpis completed={completed} cards={[
    { step: 8, label: '이번 실행 검출', value: summary?.detectedCount, footer: '이번 검증에서 발견한 예외' },
    { step: 8, label: '새 예외', value: summary?.newCount, footer: '처음 발견해 새로 등록한 건' },
    { step: 8, label: '다시 발견된 예외', value: summary?.recurringCount, footer: '기존 예외가 다시 발견된 건' },
    { step: 8, label: '해결 후 재발', value: summary?.reopenedCount, footer: '해결·반려 후 다시 열린 건' },
    { step: 8, label: '이번 실행 미검출', value: summary?.notDetectedCount, footer: '이번에는 검출되지 않은 미처리 건' },
    { step: 8, label: '미처리 예외', value: summary?.openWorkItemCount, footer: '해당 검증월의 신규·검토중 건' },
  ]} />
}
