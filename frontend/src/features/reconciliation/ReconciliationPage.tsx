import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router'
import { Button } from '../../components/Button'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { Field } from '../../components/Field'
import { FilterBar } from '../../components/FilterBar'
import { KpiCard } from '../../components/KpiCard'
import { Modal } from '../../components/Modal'
import { Pagination } from '../../components/Pagination'
import { StatusBadge } from '../../components/StatusBadge'
import { useSearchParamsState } from '../../hooks/useSearchParamsState'
import { dateTime, int, month as formatMonth, rate as rate4 } from '../../lib/format'
import { toast } from '../../stores/toasts'
import { useWorkspaceStore, withMonth } from '../../stores/workspace'
import { usePermission } from '../auth/useAuth'
import { useInsurerOptions } from '../base/api'
import {
  bulkExceptions,
  createRun,
  criteriaFromParams,
  FILTER_KEYS,
  historyQuery,
  positiveId,
  resultQuery,
} from './api'
import type { Criteria, Result, Run, RunInput, Summary } from './api'
import { Disclosure, Failure, Money, ResultBadge } from './display'
import { EMPTY, PAYMENT_STAGE, RESULT_TYPE, RUN_STATUS, text } from './labels'
import { CompareModal } from './CompareModal'
import './reconciliation.css'

const historyColumns: TableColumn<Run>[] = [
  { key: 'id', label: '실행 ID', width: '5rem', render: (row) => text(row.reconciliationRunId) },
  { key: 'month', label: '정산월', width: '7rem', render: (row) => formatMonth(row.settlementMonth) },
  {
    key: 'stage',
    label: '지급단계',
    width: '8rem',
    render: (row) =>
      row.paymentStageLabel || PAYMENT_STAGE[row.paymentStage as keyof typeof PAYMENT_STAGE] || text(row.paymentStage),
  },
  { key: 'insurer', label: '보험회사', width: '9rem', render: (row) => <Disclosure value={row.insurerName} /> },
  {
    key: 'status',
    label: '상태',
    width: '7rem',
    render: (row) => (
      <StatusBadge
        tone={
          row.status === 'COMPLETED' || row.status === 'FINALIZED'
            ? 'success'
            : row.status === 'FAILED'
              ? 'error'
              : row.status === 'RUNNING'
                ? 'info'
                : 'neutral'
        }
      >
        {row.statusLabel || RUN_STATUS[row.status as keyof typeof RUN_STATUS] || text(row.status)}
      </StatusBadge>
    ),
  },
  { key: 'target', label: '대상', width: '5rem', align: 'number', render: (row) => int(row.targetCount) },
  { key: 'matched', label: '일치', width: '5rem', align: 'number', render: (row) => int(row.matchedCount) },
  { key: 'exception', label: '불일치', width: '5rem', align: 'number', render: (row) => int(row.exceptionCount) },
  {
    key: 'rate',
    label: '일치율',
    width: '8rem',
    align: 'number',
    render: (row) => (row.matchRatePct == null ? EMPTY : `${rate4(row.matchRatePct)}%`),
  },
  {
    key: 'diff',
    label: '차액 합계',
    width: '10rem',
    align: 'number',
    render: (row) => <Money value={row.differenceTotal} difference />,
  },
  {
    key: 'period',
    label: '시작 · 완료',
    width: '17rem',
    render: (row) => `${dateTime(row.startedAt)} · ${dateTime(row.completedAt)}`,
  },
]
const resultColumns: TableColumn<Result>[] = [
  { key: 'contract', label: '계약', width: '12rem', render: (row) => text(row.contractNo) },
  { key: 'item', label: '수수료 항목', width: '10rem', render: (row) => <Disclosure value={row.commissionItemName} /> },
  { key: 'installment', label: '회차', width: '4rem', render: (row) => int(row.installmentNo) },
  {
    key: 'expectedAgent',
    label: '예상 설계사',
    width: '8rem',
    render: (row) => row.expectedAgent?.agentName || '예상 없음',
  },
  {
    key: 'actualAgent',
    label: '실제 설계사',
    width: '8rem',
    render: (row) => row.actualAgent?.agentName || '실제 없음',
  },
  {
    key: 'expected',
    label: '예상 금액',
    width: '10rem',
    align: 'number',
    render: (row) => <Money value={row.expectedTotalAmount} />,
  },
  {
    key: 'actual',
    label: '실제 금액',
    width: '10rem',
    align: 'number',
    render: (row) => <Money value={row.actualTotalAmount} />,
  },
  {
    key: 'difference',
    label: '차액',
    width: '10rem',
    align: 'number',
    render: (row) => <Money value={row.differenceAmount} difference />,
  },
  {
    key: 'type',
    label: '결과 유형',
    width: '9rem',
    render: (row) => <ResultBadge type={row.resultType} label={row.resultTypeLabel} />,
  },
  {
    key: 'primary',
    label: '주 원인',
    width: '12rem',
    render: (row) => <Disclosure value={row.primaryReason?.label || row.primaryReasonCode} />,
  },
  {
    key: 'secondary',
    label: '보조 원인',
    width: '12rem',
    render: (row) => (
      <Disclosure value={row.secondaryReasons?.map((reason) => reason.label || reason.code).join(', ')} />
    ),
  },
]

/** RECO-W01: 실행/결과는 기존 API, 이력 status 기반으로 비동기 완료를 확인한다. */
export default function ReconciliationPage() {
  const month = useWorkspaceStore((state) => state.month)
  const { params, update } = useSearchParamsState(FILTER_KEYS)
  const criteria = criteriaFromParams(params, month)
  const history = useQuery(historyQuery(criteria))
  const runId =
    !params.get('runMonth') || params.get('runMonth') === criteria.month ? positiveId(params.get('runId')) : ''
  const resultId = positiveId(params.get('resultId'))
  const resultPage = Number(positiveId(params.get('resultPage')) || 1)
  const rawType = params.get('resultType') ?? ''
  const type = Object.hasOwn(RESULT_TYPE, rawType) ? rawType : ''
  const onlyMismatch = params.get('onlyMismatch') === 'true'
  const selectedRun = history.data?.content?.find((run) => String(run.reconciliationRunId) === runId)
  const status = selectedRun?.status
  const running = status === 'RUNNING' || status === 'CREATED'
  const results = useQuery(resultQuery(runId, type, resultPage, running))
  const client = useQueryClient()
  const canProcess = usePermission('canProcess')
  const [bulkMessage, setBulkMessage] = useState<{ runId: string; message: string } | null>(null)
  const pendingRunIds = useRef(new Set<string>())
  useEffect(() => {
    if (!runId || !status) return
    if (status === 'RUNNING' || status === 'CREATED') {
      pendingRunIds.current.add(runId)
      return
    }
    // POST 또는 이력에서 관측한 진행 → 완료 전이에만 갱신한다. 조회 중 첫 응답도 끝난 뒤 최종 값을 받는다.
    const wasPending = pendingRunIds.current.delete(runId)
    if (wasPending && (status === 'COMPLETED' || status === 'FINALIZED')) {
      const queryKey = ['reconciliations', 'results', runId]
      const inFlight = client
        .getQueryCache()
        .findAll({ queryKey })
        .filter((query) => query.state.fetchStatus !== 'idle')
        .map((query) => query.promise)
      void Promise.allSettled(inFlight).then(() => client.invalidateQueries({ queryKey }))
    }
  }, [client, runId, status])
  const execution = useMutation({
    mutationFn: createRun,
    meta: { errorToast: false },
    onSuccess: (data, input) => {
      if (data.status === 'RUNNING' || data.status === 'CREATED')
        pendingRunIds.current.add(String(data.reconciliationRunId))
      void client.invalidateQueries({ queryKey: ['reconciliations', 'history'] })
      update({ ...input, runId: String(data.reconciliationRunId), runMonth: input.month, resultPage: '', resultId: '' })
      toast('대사 실행이 생성됐습니다. 이력에서 결과를 불러옵니다.', 'success')
    },
  })
  const bulk = useMutation({
    mutationFn: bulkExceptions,
    meta: { errorToast: false },
    onSuccess: (data, id) => {
      const message = `예외 ${int(data.created)}건 생성, ${int(data.skippedDuplicate)}건은 이미 있어 건너뜀.`
      setBulkMessage({ runId: id, message })
      toast(message, 'success')
      void client.invalidateQueries({ queryKey: ['exceptions'] })
    },
  })
  const selectRun = (row: Run) =>
    row.reconciliationRunId != null &&
    update(
      { runId: String(row.reconciliationRunId), runMonth: criteria.month, resultPage: '', resultId: '' },
      { resetPage: false },
    )
  const filteredRows = (results.data?.items?.content ?? []).filter(
    (row) => !onlyMismatch || row.resultType !== 'MATCHED',
  )
  const exceptionHref = `/exceptions?type=RECONCILIATION_MISMATCH&validationMonth=${criteria.month}-01&status=ALL`
  const compareColumns: TableColumn<Result>[] = [
    ...resultColumns,
    {
      key: 'detail',
      label: '상세',
      width: '5rem',
      render: (row) => (
        <Button
          variant="ghost"
          disabled={row.reconciliationResultId == null}
          aria-label={`대사 결과 ${row.reconciliationResultId} 비교 보기`}
          onClick={() => update({ resultId: String(row.reconciliationResultId) }, { resetPage: false })}
        >
          보기
        </Button>
      ),
    },
  ]
  return (
    <div className="reco-react-page">
      <header className="page-header">
        <h1 className="page-title">대사 실행 · 결과</h1>
      </header>
      <section className="surface">
        <header className="surface-header">
          <h2 className="surface-title">대사 실행</h2>
        </header>
        <RunFilters
          key={JSON.stringify(criteria)}
          criteria={criteria}
          pending={execution.isPending}
          canProcess={canProcess}
          onSearch={(input) => update({ ...input, runId: '', runMonth: '', resultPage: '', resultId: '' })}
          onRun={(input) => {
            if (canProcess && !execution.isPending) execution.mutate(input)
          }}
          bulkDisabled={
            !canProcess ||
            !runId ||
            bulk.isPending ||
            execution.isPending ||
            (status !== 'COMPLETED' && status !== 'FINALIZED')
          }
          onBulk={() => {
            if (canProcess && runId && !bulk.isPending && (status === 'COMPLETED' || status === 'FINALIZED'))
              bulk.mutate(runId)
          }}
        />
        {!canProcess && (
          <p className="reco-react-note">
            대사 실행과 불일치 예외 일괄 생성은 SETTLEMENT·SYSTEM_ADMIN 권한이 필요합니다.
          </p>
        )}
        <Failure error={execution.error || bulk.error} />
        {bulkMessage?.runId === runId && (
          <p className="reco-react-note" role="status">
            {bulkMessage.message}{' '}
            <Link to={month ? withMonth(exceptionHref, month) : exceptionHref}>예외함에서 확인</Link>
          </p>
        )}
      </section>
      <RunSummary summary={results.data?.summary} />
      {running && (
        <p role="status" className="guidance guidance-info reco-react-guidance">
          대사 실행 중입니다. 완료될 때까지 결과를 갱신합니다.
        </p>
      )}
      {status === 'FAILED' && (
        <p role="alert" className="guidance guidance-warning reco-react-guidance">
          대사 실행이 실패했습니다. 실행 이력을 확인하세요.
        </p>
      )}
      <div className="reco-react-grid">
        <section className="surface" aria-label="대사 결과" aria-busy={!!runId && results.isFetching}>
          <header className="surface-header">
            <h2 className="surface-title">대사 결과</h2>
          </header>
          <div className="reco-react-result-filters">
            <Field label="결과 유형">
              <select
                value={type}
                onChange={(e) => update({ resultType: e.target.value, resultPage: '' }, { resetPage: false })}
              >
                <option value="">전체 (11종)</option>
                {Object.entries(RESULT_TYPE).map(([code, label]) => (
                  <option key={code} value={code}>
                    {label}
                  </option>
                ))}
              </select>
            </Field>
            <label>
              <input
                type="checkbox"
                checked={onlyMismatch}
                onChange={(e) =>
                  update({ onlyMismatch: e.target.checked ? 'true' : '', resultPage: '' }, { resetPage: false })
                }
              />{' '}
              불일치만
            </label>
          </div>
          {onlyMismatch && (
            <p className="reco-react-note" role="status">
              불일치만은 현재 서버 페이지에서 일치 행을 숨깁니다. 전체 불일치 건수는 실행 요약이며, 다음 페이지에도
              결과가 있을 수 있습니다.
            </p>
          )}
          <Failure error={results.error} />
          <DataTable
            columns={compareColumns}
            rows={filteredRows}
            rowKey={(row) => String(row.reconciliationResultId)}
            caption="선택한 대사 실행의 계약별 결과"
            emptyMessage={
              !runId
                ? '대사를 실행하거나 이력을 선택하면 결과가 여기 표시됩니다.'
                : results.isPending
                  ? '대사 결과를 불러오는 중입니다.'
                  : onlyMismatch && (results.data?.items?.content?.length ?? 0) > 0 && filteredRows.length === 0
                    ? (results.data?.items?.totalPages ?? 0) > 1
                      ? '이 페이지에는 불일치가 없습니다. 다른 페이지도 확인하세요.'
                      : '이 페이지에는 불일치가 없습니다.'
                    : '조건에 맞는 자료가 없습니다.'
            }
          />
          {results.data?.summary && (
            <p className="reco-react-note">
              실행 전체 합계 — 예상 <Money value={results.data.summary.expectedTotal} /> · 실제{' '}
              <Money value={results.data.summary.actualTotal} /> · 차액{' '}
              <Money value={results.data.summary.differenceTotal} difference />
            </p>
          )}
          <Pagination
            compact
            page={resultPage}
            totalPages={results.data?.items?.totalPages ?? 0}
            onPageChange={(page) => update({ resultPage: String(page) }, { resetPage: false })}
          />
        </section>
        <section className="surface" aria-label="실행 이력" aria-busy={history.isFetching}>
          <header className="surface-header">
            <h2 className="surface-title">실행 이력</h2>
            <span>{int(history.data?.totalElements ?? 0)}건</span>
          </header>
          <Failure error={history.error} />
          <DataTable
            columns={historyColumns}
            rows={history.data?.content ?? []}
            rowKey={(row) => String(row.reconciliationRunId)}
            caption="검색조건에 해당하는 대사 실행 이력"
            onRowClick={selectRun}
            onRowActivate={selectRun}
            rowClassName={(row) => (String(row.reconciliationRunId) === runId ? 'is-selected' : '')}
          />
          <Pagination
            compact
            page={criteria.page}
            totalPages={history.data?.totalPages ?? 0}
            onPageChange={(page) => update({ page: String(page) }, { resetPage: false })}
          />
        </section>
      </div>
      {resultId && (
        <CompareModal
          resultId={resultId}
          month={month}
          onClose={() => update({ resultId: '' }, { resetPage: false })}
        />
      )}
    </div>
  )
}

function RunFilters({
  criteria,
  pending,
  canProcess,
  onSearch,
  onRun,
  bulkDisabled,
  onBulk,
}: {
  criteria: Criteria
  pending: boolean
  canProcess: boolean
  onSearch: (input: RunInput) => void
  onRun: (input: RunInput) => void
  bulkDisabled: boolean
  onBulk: () => void
}) {
  const [fields, setFields] = useState<RunInput>({
    month: criteria.month,
    stage: criteria.stage,
    insurer: criteria.insurer,
  })
  const insurers = useInsurerOptions()
  const insurer = insurers.data?.find((row) => String(row.insurerId) === fields.insurer)
  const tabCount = useWorkspaceStore((state) => state.tabs.length)
  const [monthAction, setMonthAction] = useState<{ kind: 'search' | 'run'; input: RunInput } | null>(null)
  const submit = (kind: 'search' | 'run') => {
    if (fields.month !== criteria.month) setMonthAction({ kind, input: { ...fields } })
    else if (kind === 'search') onSearch(fields)
    else onRun(fields)
  }
  return (
    <>
      <FilterBar
        className="reco-react-filters"
        icons
        onSubmit={() => submit('search')}
        actions={
          <>
            <Button
              loading={pending}
              disabled={
                !canProcess ||
                !fields.month ||
                !fields.stage ||
                !fields.insurer ||
                !insurer ||
                insurer.activeYn === false
              }
              onClick={() => submit('run')}
            >
              대사 실행
            </Button>
            <Button variant="secondary" disabled={bulkDisabled} onClick={onBulk}>
              불일치 예외 일괄 생성
            </Button>
          </>
        }
      >
        <Field label="정산월" required>
          <input type="month" value={fields.month} onChange={(e) => setFields({ ...fields, month: e.target.value })} />
        </Field>
        <Field label="지급단계">
          <select value={fields.stage} onChange={(e) => setFields({ ...fields, stage: e.target.value })}>
            <option value="">전체</option>
            {Object.entries(PAYMENT_STAGE).map(([code, label]) => (
              <option key={code} value={code}>
                {label}
              </option>
            ))}
          </select>
        </Field>
        <Field label="보험회사">
          <select
            value={fields.insurer}
            disabled={insurers.isPending || pending}
            onChange={(e) => setFields({ ...fields, insurer: e.target.value })}
          >
            <option value="">전체 (실행 이력 조회만)</option>
            {(insurers.data ?? []).map((row) => (
              <option key={row.insurerId} value={row.insurerId}>
                {row.insurerCode} · {row.insurerName}
              </option>
            ))}
          </select>
        </Field>
      </FilterBar>
      <Failure error={insurers.error} />
      {monthAction && (
        <Modal
          open
          title="기준 정산월 변경 확인"
          onClose={() => setMonthAction(null)}
          footer={
            <>
              <Button variant="secondary" onClick={() => setMonthAction(null)}>
                취소
              </Button>
              <Button
                onClick={() => {
                  const action = monthAction
                  setMonthAction(null)
                  if (action.kind === 'search') onSearch(action.input)
                  else onRun(action.input)
                }}
              >
                변경하고 {monthAction.kind === 'search' ? '조회' : '실행'}
              </Button>
            </>
          }
        >
          <p>
            기준 정산월을 {monthAction.input.month}로 변경합니다. 열린 업무 탭 {tabCount}개의 조회 기준도 함께
            변경됩니다.
          </p>
          <p>취소하면 기준월을 변경하거나 대사 실행을 요청하지 않습니다.</p>
        </Modal>
      )}
    </>
  )
}
function RunSummary({ summary }: { summary?: Summary }) {
  const matchRate = summary?.resultCount ? rate4(((summary.matchedCount ?? 0) / summary.resultCount) * 100) : EMPTY
  return (
    <section aria-label="실행 요약">
      <h2 className="surface-title">실행 요약</h2>
      <div className="reco-react-kpis">
        <KpiCard label="대상" value={summary ? int(summary.resultCount) : EMPTY} unit="건" />
        <KpiCard label="일치" value={summary ? int(summary.matchedCount) : EMPTY} unit="건" tone="success" />
        <KpiCard label="불일치" value={summary ? int(summary.exceptionCount) : EMPTY} unit="건" tone="error" />
        <KpiCard
          label="일치율 (건수 기준)"
          footer="대상·일치 건수로 계산합니다. 이력은 서버 반올림 값이므로 표시가 다를 수 있습니다."
          value={matchRate}
          unit="%"
          tone={summary && summary.resultCount === summary.matchedCount ? 'success' : 'warning'}
        />
        <KpiCard
          label="차액 합계"
          value={summary ? <Money value={summary.differenceTotal} difference /> : EMPTY}
          tone={summary?.differenceTotal === 0 ? 'success' : 'error'}
        />
      </div>
    </section>
  )
}
