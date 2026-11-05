import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Button } from '../../components/Button'
import { CellDisclosure } from '../../components/CellDisclosure'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { ListPagination } from '../../components/ListPagination'
import { Modal } from '../../components/Modal'
import { StatusBadge } from '../../components/StatusBadge'
import type { StatusTone } from '../../components/StatusBadge'
import { ApiError } from '../../lib/api/errors'
import { dateTime, int } from '../../lib/format'
import { nameWithCode } from '../../lib/screenText'
import { Amount } from '../cap/badges'
import { contractEventsQuery, contractStatusEffectsQuery } from './api'
import type { ContractStatusEffect, ContractStatusEvent } from './api'
import { ContractFacts, ContractLoadState, QueryContent } from './detailParts'
import {
  contractErrorText,
  FOLLOW_UP_REFETCH_MS,
  followUpRunning,
  hasExhaustedFollowUp,
  sortStatusEvents,
} from './detailState'
import type { StatusSort } from './detailState'
import {
  contractStatusLabels,
  effectEntityLabels,
  effectStateLabel,
  eventFollowUpLabels,
  eventReasonLabels,
  followUpFailureText,
  followUpEmptyEffects,
  processingJobLabels,
  processingStatusLabels,
  responseLabel,
  sourceSystemLabels,
} from './labels'

type FollowUpJob = NonNullable<NonNullable<ContractStatusEvent['followUp']>['jobs']>[number]
type Processing = NonNullable<ContractStatusEvent['processings']>[number]
type Summary = NonNullable<Processing['summary']>

const NO_DATA = '자료 없음'
const followUpTones: Record<string, StatusTone> = {
  NOT_REQUIRED: 'neutral',
  PENDING: 'info',
  PROCESSING: 'info',
  FAILED: 'error',
  EXHAUSTED: 'error',
  SUCCEEDED: 'success',
}
const processingTones: Record<string, StatusTone> = { SUCCEEDED: 'success', FAILED: 'error' }
const effectTones: Record<string, StatusTone> = {
  HELD: 'review',
  HOLD: 'review',
  RELEASED: 'info',
  PLANNED: 'info',
  CANCELLED: 'neutral',
  EXCLUDED: 'neutral',
}

const jobLabel = (job?: string) => processingJobLabels[job ?? ''] ?? '처리 작업'
const transition = (event: ContractStatusEvent) =>
  `${event.previousStatus ? responseLabel(contractStatusLabels, event.previousStatus, event.previousStatusLabel) : '최초'} → ${responseLabel(contractStatusLabels, event.newStatus, event.newStatusLabel)}`
// 원천에 없는 처리자·사유를 현재 사용자나 다른 값으로 채우지 않는다.
const registrant = (event: ContractStatusEvent) =>
  event.registeredBy ? nameWithCode(event.registeredBy.userName, event.registeredBy.loginId) : NO_DATA
const reasonText = (event: ContractStatusEvent) =>
  event.reason || (event.reasonCode ? responseLabel(eventReasonLabels, event.reasonCode) : NO_DATA)

function summaryText(summary: Summary) {
  const parts = [
    summary.heldPayments ? `지급 보류 ${int(summary.heldPayments)}건` : '',
    summary.resumedPayments ? `보류 해제 ${int(summary.resumedPayments)}건` : '',
    summary.changedScheduleLines ? `회차 변경 ${int(summary.changedScheduleLines)}건` : '',
    summary.excludedCount ? `처리 제외 ${int(summary.excludedCount)}건` : '',
  ].filter(Boolean)
  return parts.length ? parts.join(', ') : '바뀐 대상 0건'
}

function jobNotes(job: FollowUpJob) {
  const tries = `${int(job.attemptCount)}/${int(job.maxAttempts)}회 시도`
  switch (job.status) {
    case 'FAILED':
      return [`${tries}, 다음 재시도 ${dateTime(job.nextRetryAt)}`, `실패 사유: ${followUpFailureText(job.errorCode)}`]
    case 'EXHAUSTED':
      return [`${tries}, 운영 담당 확인 필요`, `실패 사유: ${followUpFailureText(job.errorCode)}`]
    case 'PENDING':
      return job.attemptCount ? [`${tries}, 다음 실행 대기`] : []
    case 'SUCCEEDED':
      return (job.attemptCount ?? 0) > 1 ? [`재시도 후 성공, ${tries}`] : []
    default:
      return []
  }
}

function FollowUpStatus({ event }: { event: ContractStatusEvent }) {
  const jobs = event.followUp?.jobs ?? []
  return (
    <>
      <StatusBadge
        optionCount={Object.keys(eventFollowUpLabels).length}
        tone={followUpTones[event.followUp?.status ?? ''] ?? 'neutral'}
      >
        {responseLabel(eventFollowUpLabels, event.followUp?.status)}
      </StatusBadge>
      {jobs.flatMap((job) =>
        jobNotes(job).map((note) => (
          <span className="contract-follow-up-note" key={`${job.job}-${note}`}>
            {jobs.length > 1 ? `${jobLabel(job.job)}: ${note}` : note}
          </span>
        )),
      )}
    </>
  )
}

function ProcessingList({ rows, label, attempts = false }: { rows: Processing[]; label: string; attempts?: boolean }) {
  if (!rows.length) return null
  return (
    <ul className="contract-processing-list" aria-label={label}>
      {rows.map((row, index) => (
        <li className="contract-processing-row" key={`${row.processingJob}-${row.processedAt}-${index}`}>
          <strong className="contract-processing-job">{jobLabel(row.processingJob)}</strong>
          <StatusBadge
            optionCount={Object.keys(processingStatusLabels).length}
            tone={processingTones[row.processingStatus ?? ''] ?? 'neutral'}
          >
            {responseLabel(processingStatusLabels, row.processingStatus)}
          </StatusBadge>
          <time dateTime={row.processedAt}>{dateTime(row.processedAt)}</time>
          {row.summary && <span className="contract-follow-up-note">{summaryText(row.summary)}</span>}
          {attempts && row.errorCode && (
            <span className="contract-follow-up-note">실패 사유: {followUpFailureText(row.errorCode)}</span>
          )}
        </li>
      ))}
    </ul>
  )
}

/** 표에는 작업별 마지막 행만 둔다. 시도 전체는 영향 창에서 본다. */
const latestByJob = (rows: Processing[] = []) => [...new Map(rows.map((row) => [row.processingJob, row])).values()]

const historyColumns = (onOpen: (eventId: number) => void): TableColumn<ContractStatusEvent>[] => [
  { key: 'seq', label: '순번', width: '3.25rem', align: 'center', render: (row) => int(row.eventSeq) },
  {
    key: 'transition',
    label: '변경 전→후',
    width: '6.5rem',
    render: (row) => (
      <>
        {transition(row)}
        <span className="contract-follow-up-note">
          {sourceSystemLabels[row.sourceSystem ?? ''] ?? '계약 상태 사건'}
        </span>
      </>
    ),
  },
  { key: 'effectiveAt', label: '효력일시', width: '6.125rem', render: (row) => dateTime(row.effectiveAt) },
  { key: 'receivedAt', label: '접수일시', width: '6.125rem', render: (row) => dateTime(row.receivedAt) },
  { key: 'recordedAt', label: '처리일시', width: '6.125rem', render: (row) => dateTime(row.recordedAt) },
  { key: 'registeredBy', label: '처리자', width: '5.25rem', render: registrant },
  { key: 'reason', label: '사유', width: '7rem', render: (row) => <CellDisclosure preview={reasonText(row)} /> },
  {
    key: 'followUp',
    label: '후속 상태',
    width: '11.75rem',
    render: (row) => (
      <div className="contract-follow-up">
        <FollowUpStatus event={row} />
        <ProcessingList rows={latestByJob(row.processings)} label="후속 처리 작업" />
      </div>
    ),
  },
  {
    key: 'effects',
    label: '영향',
    width: '6rem',
    align: 'center',
    render: (row) => (
      <Button
        data-guide-feature="features.contract.status-history.effects"
        variant="ghost"
        disabled={row.eventId == null}
        aria-label={`사건 ${int(row.eventSeq)} 영향 보기`}
        onClick={() => onOpen(row.eventId!)}
      >
        영향 보기
      </Button>
    ),
  },
]

const effectColumns: TableColumn<ContractStatusEffect>[] = [
  {
    key: 'target',
    label: '대상',
    width: '9rem',
    render: (row) => `${effectEntityLabels[row.entityType ?? ''] ?? '확인 필요'} #${row.entityId ?? '-'}`,
  },
  { key: 'previous', label: '이전 상태', width: '7rem', render: (row) => effectStateLabel(row, row.previousState) },
  {
    key: 'next',
    label: '이후 상태',
    width: '10rem',
    render: (row) => (
      <StatusBadge optionCount={Object.keys(effectTones).length} tone={effectTones[row.nextState ?? ''] ?? 'neutral'}>
        {effectStateLabel(row, row.nextState)}
      </StatusBadge>
    ),
  },
  {
    key: 'amount',
    label: '금액',
    width: '7rem',
    align: 'number',
    render: (row) => (row.amount == null ? NO_DATA : <Amount value={row.amount} />),
  },
  {
    key: 'policy',
    label: '정책',
    width: '7rem',
    render: (row) => (row.policyVersionId == null ? NO_DATA : `정책 버전 #${row.policyVersionId}`),
  },
  { key: 'reason', label: '처리/제외 사유', width: '20rem', render: (row) => row.reason || NO_DATA },
]

function StatusEffectsPanel({ id, event, onClose }: { id: string; event: ContractStatusEvent; onClose: () => void }) {
  const [page, setPage] = useState(1)
  const status = event.followUp?.status
  const effects = useQuery(contractStatusEffectsQuery(id, event.eventId!, page, status))
  return (
    <Modal
      open
      title={`사건 ${int(event.eventSeq)} 영향`}
      className="modal-large"
      onClose={onClose}
      footer={
        <Button
          data-guide-feature="features.contract.status-history.effects-close"
          variant="secondary"
          onClick={onClose}
        >
          닫기
        </Button>
      }
    >
      <ContractFacts
        items={[
          ['변경 전→후', transition(event)],
          ['효력일시', dateTime(event.effectiveAt)],
          ['접수일시', dateTime(event.receivedAt)],
          ['처리일시', dateTime(event.recordedAt)],
          ['처리자', registrant(event)],
          ['사유', reasonText(event)],
        ]}
      />
      <h3 className="contract-effects-heading">후속 처리</h3>
      <div className="contract-follow-up">
        <FollowUpStatus event={event} />
        <ProcessingList rows={event.processings ?? []} label="처리 이력" attempts />
      </div>
      <QueryContent query={effects}>
        {(data) => (
          <>
            <h3 className="contract-effects-heading">영향 {int(data.totalElements)}건</h3>
            {effects.isPlaceholderData && (
              <p className="contract-follow-up-note" role="status">
                갱신 중입니다. 이전 결과를 표시합니다.
              </p>
            )}
            <DataTable
              className="contract-effects-table"
              caption="사건 영향"
              columns={effectColumns}
              rows={data.content ?? []}
              rowKey={(row) => `${row.entityType}-${row.entityId}-${row.processingJob}`}
              emptyMessage={responseLabel(followUpEmptyEffects, status)}
            />
            <ListPagination
              page={page}
              totalPages={data.totalPages ?? 1}
              label="사건 영향 페이지"
              onPageChange={setPage}
            />
          </>
        )}
      </QueryContent>
    </Modal>
  )
}

export function ContractStatusHistory({ id }: { id: string }) {
  const history = useQuery({
    ...contractEventsQuery(id),
    refetchInterval: (query) => (followUpRunning(query.state.data) ? FOLLOW_UP_REFETCH_MS : false),
  })
  const [sort, setSort] = useState<StatusSort>('effectiveAt')
  const [selection, setSelection] = useState<{ eventId: number; open: boolean } | null>(null)
  const columns = useMemo(() => historyColumns((eventId) => setSelection({ eventId, open: true })), [])
  // 재조회 실패에도 보던 이력과 선택을 유지한다. 권한이 사라졌으면(403) 이전 결과를 계속 보여 주지 않는다.
  const denied = history.error instanceof ApiError && history.error.status === 403
  const events = denied ? undefined : history.data
  const opened = selection?.open ? events?.find((event) => event.eventId === selection.eventId) : undefined
  return (
    <section
      data-guide-feature="features.contract.contract-tabs.section-3"
      data-guide-target="contract.statusHistory"
      className="contract-history-panel"
      aria-labelledby="contract-history-title"
    >
      <header className="contract-history-panel-header">
        <div>
          <h3 id="contract-history-title">상태이력</h3>
          <p>상태 변경과 그 처리 결과를 보여 줍니다.</p>
        </div>
        <div className="contract-history-sort" role="group" aria-label="상태이력 정렬 기준">
          <Button
            data-guide-feature="features.contract.contract-tabs.button-3"
            className={`contract-history-sort-button ${sort === 'effectiveAt' ? 'is-active' : ''}`}
            variant="secondary"
            aria-pressed={sort === 'effectiveAt'}
            onClick={() => setSort('effectiveAt')}
          >
            효력 순
          </Button>
          <Button
            data-guide-feature="features.contract.contract-tabs.button-4"
            className={`contract-history-sort-button ${sort === 'receivedAt' ? 'is-active' : ''}`}
            variant="secondary"
            aria-pressed={sort === 'receivedAt'}
            onClick={() => setSort('receivedAt')}
          >
            접수 순
          </Button>
        </div>
      </header>
      {events ? (
        <>
          {history.isRefetchError && (
            <ContractLoadState
              message={`${contractErrorText(history.error, '최신 상태를 불러오지 못했습니다.')} 이전 조회 결과를 표시합니다.`}
              error
              retry={() => void history.refetch()}
            />
          )}
          {hasExhaustedFollowUp(events) && (
            <p className="guidance guidance-warning contract-history-guidance">
              <span className="material-symbols-rounded guidance-icon" aria-hidden="true">
                warning
              </span>
              <span className="guidance-copy">
                자동 재시도를 모두 쓴 후속 처리가 있습니다. 이 계약의 뒤 사건도 함께 멈춥니다. 운영 담당 확인이
                필요합니다.
              </span>
            </p>
          )}
          {followUpRunning(events) && (
            <p className="contract-history-note" role="status">
              끝나지 않은 후속 처리가 있어 {FOLLOW_UP_REFETCH_MS / 1000}초마다 다시 조회합니다.
            </p>
          )}
          <DataTable
            className="contract-history-table"
            caption="상태 사건"
            columns={columns}
            rows={sortStatusEvents(events, sort)}
            rowKey={(row) => String(row.eventId ?? `${row.sourceSystem ?? ''}-${row.sourceEventKey ?? row.eventSeq}`)}
            selectedKeys={selection ? [String(selection.eventId)] : []}
            emptyMessage="등록된 상태이력이 없습니다."
          />
        </>
      ) : (
        <QueryContent query={history}>{() => null}</QueryContent>
      )}
      {opened && (
        <StatusEffectsPanel
          key={opened.eventId}
          id={id}
          event={opened}
          onClose={() => setSelection({ eventId: opened.eventId!, open: false })}
        />
      )}
    </section>
  )
}
