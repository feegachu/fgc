import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { Button } from '../../components/Button'
import { Modal } from '../../components/Modal'
import { date, int, month as formatMonth, rate } from '../../lib/format'
import { withMonth } from '../../stores/workspace'
import { detailQuery } from './api'
import type { Detail, Match } from './api'
import { Disclosure, Failure, Money, ResultBadge } from './display'
import { EMPTY, PAYMENT_STAGE, text } from './labels'

export function CompareModal({
  resultId,
  month,
  onClose,
}: {
  resultId: string
  month: string | null
  onClose: () => void
}) {
  const detail = useQuery(detailQuery(resultId))
  return (
    <Modal
      open
      title="대사 비교 상세"
      className="modal-large reco-react-modal"
      onClose={onClose}
      footer={
        <Button variant="secondary" onClick={onClose}>
          닫기
        </Button>
      }
    >
      <Failure error={detail.error} />
      {detail.isPending ? (
        <p role="status">불러오는 중입니다.</p>
      ) : (
        detail.data && <CompareContent detail={detail.data} month={month} />
      )}
    </Modal>
  )
}
function CompareContent({ detail, month }: { detail: Detail; month: string | null }) {
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
      <p>
        대사 결과 #{detail.reconciliationResultId} ·{' '}
        {PAYMENT_STAGE[stage as keyof typeof PAYMENT_STAGE] || stage || '지급단계 미확인'}
      </p>
      <p>예상 스케줄과 실제 지급 건을 필드별로 비교합니다.</p>
      <ResultBadge type={detail.resultType} label={detail.resultTypeLabel} />
      <dl className="reco-react-compare-summary">
        <div>
          <dt>계약 · 항목 · 회차</dt>
          <dd>
            {text(detail.contractNo)} · {text(detail.commissionItemName)} ·{' '}
            {detail.installmentNo == null ? EMPTY : `${int(detail.installmentNo)}회차`}
          </dd>
        </div>
        <div>
          <dt>결과 ID</dt>
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
          <dt>매칭키</dt>
          <dd>
            <Disclosure value={detail.matchGroupKey} />
          </dd>
        </div>
      </dl>
      <div className="reco-react-compare-grid">
        <SourceSide title="예상" matches={expected} detail={detail} expected />
        <SourceSide title="실제" matches={actual} detail={detail} expected={false} />
      </div>
      <section className="surface reco-react-note" aria-label="차액 및 분류">
        <p>
          예상 금액 <Money value={detail.expectedTotalAmount} /> · 실제 금액 <Money value={detail.actualTotalAmount} />
        </p>
        <p>
          결과 유형 <ResultBadge type={detail.resultType} label={detail.resultTypeLabel} />
        </p>
        <p>주 원인 {detail.primaryReason?.label || text(detail.primaryReasonCode)}</p>
        <p>보조 원인 {detail.secondaryReasons?.map((reason) => reason.label || reason.code).join(', ') || EMPTY}</p>
        <p>
          차액 = 실제 <Money value={detail.actualTotalAmount} /> − 예상 <Money value={detail.expectedTotalAmount} /> ={' '}
          <Money value={detail.differenceAmount} difference />
        </p>
        <p>허용오차 0원 · 시스템 계산 결과</p>
      </section>
      <nav className="reco-react-links" aria-label="관련 화면">
        {scheduleId != null && (
          <Link className="button button-secondary" to={href(`/schedules/${scheduleId}`)}>
            스케줄 상세
          </Link>
        )}
        <Link className="button button-secondary" to={href('/transactions')}>
          지급 건 목록
        </Link>
        <Link className="button button-secondary" to={href('/journals')}>
          관련 분개
        </Link>
        <Link className="button button-secondary" to={href('/exceptions')}>
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
  const agentLabel = agent?.agentName ? `${agent.agentName}${agent.agentCode ? ` · ${agent.agentCode}` : ''}` : '없음'
  return (
    <section className="surface">
      <header className="surface-header">
        <h3 className="surface-title">{title}</h3>
        <span>{matches.length}개 원천</span>
      </header>
      {!matches.length ? (
        <p className="empty-state">{expected ? '예상 없음' : '실제 없음'}</p>
      ) : (
        matches.map((match, index) => (
          <div className="reco-react-source" key={`${match.matchSeq}-${index}`}>
            <h4>
              {title} 원천 {index + 1} ·{' '}
              {expected
                ? `schedule_line #${match.scheduleLineId ?? EMPTY}`
                : `commission_transaction #${match.commissionTransactionId ?? EMPTY}`}
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
                    <dt>귀속 ID</dt>
                    <dd>
                      {match.transactionAttributionId == null
                        ? EMPTY
                        : `attribution #${match.transactionAttributionId}`}
                    </dd>
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
            <p>저장된 대사 원천</p>
          </div>
        ))
      )}
    </section>
  )
}
