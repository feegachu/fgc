import { nameWithCode } from '../../lib/screenText'
import { ScreenHelp } from '../assistant/guide/ScreenHelp'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { Button } from '../../components/Button'
import { Modal } from '../../components/Modal'
import { date, int, month as formatMonth, rate } from '../../lib/format'
import { withMonth } from '../../stores/workspace'
import { detailQuery } from './api'
import type { Detail, Match } from './api'
import { Failure, Money, ResultBadge } from './display'
import { EMPTY, PAYMENT_STAGE, text } from './labels'

export function CompareModal({
  resultId,
  month,
  onClose,
  revised = false,
}: {
  resultId: string
  month: string | null
  onClose: () => void
  revised?: boolean
}) {
  const detail = useQuery(detailQuery(resultId))
  return (
    <Modal
      open
      title="대사 비교 상세"
      className="modal-large reco-react-modal"
      onClose={onClose}
      footer={
        <Button data-guide-feature="features.reconciliation.compare-modal.button" variant="secondary" onClick={onClose}>
          닫기
        </Button>
      }
    >
      <Failure error={detail.error} />
      <ScreenHelp screenId="RECO-W02" variant="detail" />
      {detail.isPending ? (
        <p role="status">불러오는 중입니다.</p>
      ) : (
        detail.data && <CompareContent detail={detail.data} month={month} revised={revised} />
      )}
    </Modal>
  )
}
function CompareContent({ detail, month, revised }: { detail: Detail; month: string | null; revised: boolean }) {
  const [tab, setTab] = useState(0)
  const tabs = ['비교 요약', '예상 원천', '실제 지급', '차액·분류 근거', '관련 화면']
  const snapshot = detail.detailSnapshot
  const stage =
    snapshot != null &&
    typeof snapshot === 'object' &&
    'paymentStage' in snapshot &&
    typeof snapshot.paymentStage === 'string'
      ? snapshot.paymentStage
      : ''
  const matches = detail.matches ?? []
  const expected = matches.filter((match) => match.matchRole === 'EXPECTED' || match.matchRole === 'BOTH')
  const actual = matches.filter((match) => match.matchRole === 'ACTUAL' || match.matchRole === 'BOTH')
  const scheduleId = matches.find((match) => match.scheduleHeaderId != null)?.scheduleHeaderId
  const href = (path: string) => (month ? withMonth(path, month) : path)
  return (
    <>
      {revised && <div className="reco-detail-tabs" role="tablist" aria-label="대사 비교 상세 항목">
        {tabs.map((label, index) => <button data-guide-feature="features.compare-modal.button-1" type="button" key={label} role="tab" id={`reco-tab-${index}`} aria-selected={tab === index}
          aria-controls={`reco-panel-${index}`} tabIndex={tab === index ? 0 : -1} onClick={() => setTab(index)} onKeyDown={event => {
            const next = event.key === 'ArrowRight' ? (index + 1) % tabs.length : event.key === 'ArrowLeft' ? (index + tabs.length - 1) % tabs.length : null
            if (next != null) { event.preventDefault(); setTab(next); document.getElementById(`reco-tab-${next}`)?.focus() }
          }}>{label}</button>)}
      </div>}
      <p>
        대사 결과, {PAYMENT_STAGE[stage as keyof typeof PAYMENT_STAGE] || stage || '지급단계 미확인'}
      </p>
      <p>예상 스케줄과 실제 지급 건을 항목별로 비교합니다.</p>
      <ResultBadge type={detail.resultType} label={detail.resultTypeLabel} />
      <div id="reco-panel-0" role={revised ? 'tabpanel' : undefined} aria-labelledby={revised ? 'reco-tab-0' : undefined} hidden={revised && tab !== 0}>
      <dl className="reco-react-compare-summary">
        <div>
          <dt>계약, 항목, 회차</dt>
          <dd>
            {text(detail.contractNo)}, {text(detail.commissionItemName)},{' '}
            {detail.installmentNo == null ? EMPTY : `${int(detail.installmentNo)}회차`}
          </dd>
        </div>
        <div>
          <dt>대사 결과 번호</dt>
          <dd>{detail.reconciliationResultId}</dd>
        </div>
        <div>
          <dt>차액</dt>
          <dd>
            <Money value={detail.differenceAmount} difference />
          </dd>
        </div>
        <div>
          <dt>주 원인</dt>
          <dd>{detail.primaryReason?.label || text(detail.primaryReasonCode)}</dd>
        </div>
        <div>
          <dt>비교 기준</dt>
          {/* 내부 키 원문은 숨기고 맞춰 보는 기준만 보인다(RECO-W01 매칭키). */}
          <dd>계약번호, 지급단계, 설계사, 수수료 항목, 회차</dd>
        </div>
      </dl>
      </div>
      <div className={`reco-react-compare-grid${revised ? ' is-tabbed' : ''}`}>
        <div id="reco-panel-1" role={revised ? 'tabpanel' : undefined} aria-labelledby={revised ? 'reco-tab-1' : undefined} hidden={revised && tab !== 1}><SourceSide title="예상" matches={expected} detail={detail} expected /></div>
        <div id="reco-panel-2" role={revised ? 'tabpanel' : undefined} aria-labelledby={revised ? 'reco-tab-2' : undefined} hidden={revised && tab !== 2}><SourceSide title="실제" matches={actual} detail={detail} expected={false} /></div>
      </div>
      <section data-guide-feature="features.reconciliation.compare-modal.section" data-guide-target="reconciliation.detail" className="surface reco-react-note" aria-label="차액 및 분류"
        id="reco-panel-3" role={revised ? 'tabpanel' : undefined} aria-labelledby={revised ? 'reco-tab-3' : undefined} hidden={revised && tab !== 3}>
        <p>
          예상 금액 <Money value={detail.expectedTotalAmount} />, 실제 금액 <Money value={detail.actualTotalAmount} />
        </p>
        <p>
          결과 유형 <ResultBadge type={detail.resultType} label={detail.resultTypeLabel} />
        </p>
        <p>주 원인 {detail.primaryReason?.label || text(detail.primaryReasonCode)}</p>
        <p>보조 원인 {detail.secondaryReasons?.map((reason) => reason.label || reason.code).join(', ') || EMPTY}</p>
        <p>
          차액 <Money value={detail.differenceAmount} difference /> (실제에서 예상을 뺀 금액)
        </p>
        <p>허용오차 0원, 시스템 계산 결과</p>
      </section>
      <nav className="reco-react-links" aria-label="관련 화면" id="reco-panel-4" role={revised ? 'tabpanel' : undefined} aria-labelledby={revised ? 'reco-tab-4' : undefined} hidden={revised && tab !== 4}>
        {scheduleId != null && (
          <Link data-guide-feature="features.reconciliation.compare-modal.link" className="button button-secondary" to={href(`/schedules/${scheduleId}`)}>
            스케줄 상세
          </Link>
        )}
        <Link data-guide-feature="features.reconciliation.compare-modal.link-2" className="button button-secondary" to={href('/transactions')}>
          지급 건 목록
        </Link>
        <Link data-guide-feature="features.reconciliation.compare-modal.link-3" className="button button-secondary" to={href('/journals')}>
          관련 분개
        </Link>
        <Link data-guide-feature="features.reconciliation.compare-modal.link-4" className="button button-secondary" to={href('/exceptions')}>
          예외 건
        </Link>
      </nav>
    </>
  )
}
function SourceSide({
  title,
  matches,
  detail,
  expected,
}: {
  title: string
  matches: Match[]
  detail: Detail
  expected: boolean
}) {
  const agent = expected ? detail.expectedAgent : detail.actualAgent
  const agentLabel = agent?.agentName ? nameWithCode(agent.agentName, agent.agentCode) : '없음'
  return (
    <section data-guide-feature="features.reconciliation.compare-modal.section-2" className="surface">
      <header className="surface-header">
        <h3 className="surface-title">{title}</h3>
        <span>{matches.length}건</span>
      </header>
      {!matches.length ? (
        <p className="empty-state">{expected ? '예상 없음' : '실제 없음'}</p>
      ) : (
        matches.map((match, index) => (
          <div className="reco-react-source" key={`${match.matchSeq}-${index}`}>
            <h4>
              {title} {index + 1}, {expected ? '예상 스케줄' : '지급 건'}
            </h4>
            <dl>
              <div>
                <dt>회차</dt>
                <dd>{match.installmentNo == null ? EMPTY : `${int(match.installmentNo)}회차`}</dd>
              </div>
              <div>
                <dt>{expected ? '지급예정일' : '정산월'}</dt>
                <dd>{expected ? date(match.dueDate) : formatMonth(match.settlementMonth)}</dd>
              </div>
              <div>
                <dt>수령자</dt>
                <dd>{agentLabel}</dd>
              </div>
              {expected ? (
                <>
                  <div>
                    <dt>기준금액</dt>
                    <dd>
                      <Money value={match.basisAmount} />
                    </dd>
                  </div>
                  <div>
                    <dt>적용 요율</dt>
                    <dd>{match.ratePct == null ? EMPTY : `${rate(match.ratePct)}%`}</dd>
                  </div>
                </>
              ) : (
                <>
                  <div>
                    <dt>귀속 번호</dt>
                    <dd>{match.transactionAttributionId ?? EMPTY}</dd>
                  </div>
                  <div>
                    <dt>지급일</dt>
                    <dd>{date(match.referenceDate || match.attributionDate)}</dd>
                  </div>
                </>
              )}
              <div>
                <dt>{title} 금액</dt>
                <dd>
                  <Money value={match.matchedAmount} />
                </dd>
              </div>
            </dl>
            <p>저장된 대사 근거</p>
          </div>
        ))
      )}
    </section>
  )
}
