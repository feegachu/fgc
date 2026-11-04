import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useOutletContext, useSearchParams } from 'react-router'
import type { ShellContext } from '../../app/shell/AppShell'
import { Button } from '../../components/Button'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { Field } from '../../components/Field'
import { FilterBar } from '../../components/FilterBar'
import { ListHeader } from '../../components/ListHeader'
import { ListPagination } from '../../components/ListPagination'
import { Modal } from '../../components/Modal'
import { ResizableSplit } from '../../components/ResizableSplit'
import { SearchSelect } from '../../components/SearchSelect'
import { dateTime } from '../../lib/format'
import { sortBinding } from '../../lib/tableSort'
import { toast } from '../../stores/toasts'
import { useInsurerOptions } from '../base/api'
import { useEntityChoices } from '../base/selection'
import { QueryState } from '../reference/ReferenceUI'
import { ACQUISITION_SORT_KEYS, acquisitionListQuery, criteriaFromParams, useRequestAcquisitionCheck } from './api'
import type { AcquisitionCheck, AcquisitionItem, AcquisitionRequest, Criteria } from './api'
import { AcquisitionCostResult, AcquisitionFailure, AcquisitionStatus } from './AcquisitionCostResult'
import { amountText, policyText } from './labels'

const statuses = [
  { value: 'NORMAL', label: '한도 이내' },
  { value: 'VIOLATION', label: '한도 초과' },
  { value: 'REVIEW_REQUIRED', label: '검토 필요' },
  { value: 'NOT_APPLICABLE', label: '적용 대상 아님' },
]
const limitCell = (item: AcquisitionItem | null | undefined, row: AcquisitionCheck) =>
  item ? (
    <AcquisitionStatus status={item.status} label={item.statusLabel} />
  ) : row.resultStatus === 'NOT_APPLICABLE' ? (
    '-'
  ) : (
    '자료 없음'
  )
// 좁은 폭에서도 판정과 두 한도가 먼저 보이게 앞에 둔다. 나머지 칸은 표를 밀어서 본다.
const columns: TableColumn<AcquisitionCheck>[] = [
  {
    key: 'contract',
    label: '계약번호',
    sortKey: 'contractNo',
    sortable: true,
    width: '13rem',
    cellClassName: 'tabular-nums',
    render: (row) => row.contractNo,
  },
  {
    key: 'result',
    label: '판정',
    width: '7.5rem',
    render: (row) => <AcquisitionStatus status={row.resultStatus} label={row.resultStatusLabel} />,
  },
  { key: 'upfront', label: '선지급 결과', width: '6.5rem', render: (row) => limitCell(row.upfront, row) },
  { key: 'maintenance', label: '유지관리 결과', width: '6.5rem', render: (row) => limitCell(row.maintenance, row) },
  {
    key: 'base',
    label: '계약체결비용 기준액',
    width: '8.5rem',
    align: 'number',
    render: (row) => (row.resultStatus === 'NOT_APPLICABLE' ? '-' : amountText(row.acquisitionCost?.amount)),
  },
  { key: 'stage', label: '지급단계', width: '6.5rem', render: (row) => row.paymentStageLabel },
  {
    key: 'product',
    label: '적용 상품·기초서류',
    width: '14rem',
    render: (row) => `${row.productName}, ${row.basicDocumentVersion}`,
  },
  {
    key: 'policy',
    label: '적용 정책 버전',
    width: '13rem',
    render: (row) => (row.resultStatus === 'NOT_APPLICABLE' ? '-' : policyText(row.limitPolicy)),
  },
  {
    key: 'checkedAt',
    label: '검증 일시',
    sortKey: 'checkedAt',
    sortable: true,
    width: '9rem',
    cellClassName: 'tabular-nums',
    render: (row) => dateTime(row.checkedAt),
  },
]

function RequestCheckModal({ onClose, onDone }: { onClose: () => void; onDone: (check: AcquisitionCheck) => void }) {
  const [contractId, setContractId] = useState('')
  const [stage, setStage] = useState<AcquisitionRequest['paymentStage']>('GA_TO_FC')
  const [submitted, setSubmitted] = useState(false)
  const contracts = useEntityChoices('contracts', {}, [contractId])
  const client = useQueryClient()
  const mutation = useRequestAcquisitionCheck()
  function submit() {
    setSubmitted(true)
    if (!contractId) {
      document.getElementById('acq-contract')?.focus()
      return
    }
    if (mutation.isPending) return
    mutation.mutate({ contractId: Number(contractId), paymentStage: stage }, { onSuccess: onDone })
  }
  return (
    <Modal
      open
      title="계약체결비용 한도 검증 요청"
      className="acq-request-modal"
      closeOnBackdrop={!mutation.isPending}
      closeOnEscape={!mutation.isPending}
      onClose={() => {
        if (!mutation.isPending) onClose()
      }}
      footer={
        <>
          <Button
            data-guide-feature="features.acquisition-cost.acquisition-cost-page.button"
            variant="secondary"
            disabled={mutation.isPending}
            onClick={onClose}
          >
            취소
          </Button>
          <Button
            data-guide-feature="features.acquisition-cost.acquisition-cost-page.button-2"
            loading={mutation.isPending}
            disabled={mutation.isPending}
            onClick={submit}
          >
            검증 요청
          </Button>
        </>
      }
    >
      <div className="acq-request-body">
        <p>활성 예정 스케줄을 정책 기준으로 판정해 결과를 저장합니다. 금액과 요율은 입력하지 않습니다.</p>
        <Field label="계약" required error={submitted && !contractId ? '계약을 고르세요.' : undefined}>
          <SearchSelect
            data-guide-feature="features.acquisition-cost.acquisition-cost-page.search-select"
            id="acq-contract"
            value={contractId}
            disabled={mutation.isPending}
            source={contracts.source}
            selected={contracts.data.find((row) => String(row.contractId) === contractId)}
            placeholder="계약번호 검색"
            onSelect={(id, row) => {
              contracts.remember(row)
              setContractId(id)
              mutation.reset()
            }}
          />
        </Field>
        <Field label="지급단계" required>
          <select
            data-guide-feature="features.acquisition-cost.acquisition-cost-page.select"
            className="select-control"
            value={stage}
            disabled={mutation.isPending}
            onChange={(event) => {
              setStage(event.target.value as AcquisitionRequest['paymentStage'])
              mutation.reset()
            }}
          >
            <option value="GA_TO_FC">GA→설계사</option>
            <option value="INSURER_TO_GA">원수사→GA</option>
          </select>
        </Field>
        {mutation.error && (
          <AcquisitionFailure
            error={mutation.error}
            retry={() => void client.invalidateQueries({ queryKey: ['acquisition-cost'] })}
          />
        )}
      </div>
    </Modal>
  )
}

export default function AcquisitionCostPage() {
  const { user } = useOutletContext<ShellContext>()
  const [params, setParams] = useSearchParams()
  const criteria = criteriaFromParams(params)
  const query = useQuery(acquisitionListQuery(criteria))
  const insurers = useInsurerOptions()
  const [selection, setSelection] = useState<{ source: string; id: number } | null>(null)
  const [requesting, setRequesting] = useState(false)
  // 조회 전 계약번호 입력은 지금 URL 값에 대해서만 유지한다.
  const [draft, setDraft] = useState<{ source: string; value: string } | null>(null)
  const contractNo = draft?.source === criteria.contractNo ? draft.value : criteria.contractNo
  const source = JSON.stringify(criteria)
  const rows = query.data?.content ?? []
  const selected =
    rows.find((row) => selection?.source === source && row.acquisitionCostCheckId === selection.id) ?? rows[0]
  function patch(values: Partial<Criteria>, resetPage = true) {
    setSelection(null)
    setParams((current) => {
      const next = new URLSearchParams(current)
      if (resetPage) next.delete('page')
      Object.entries({ contractNo: contractNo.trim(), ...values }).forEach(([key, value]) => {
        if (value === '') next.delete(key)
        else next.set(key, String(value))
      })
      return next
    })
  }
  function reset() {
    setDraft(null)
    patch({ resultStatus: '', paymentStage: '', insurerId: '', contractNo: '', sort: '' })
  }
  const busy = query.isLoading
  return (
    <div className="acq-page">
      <header className="page-header">
        <div className="page-header-copy">
          <h1 className="page-title">계약체결비용 한도 검증</h1>
          <p className="page-description">
            선지급과 유지관리 예정 금액을 계약체결비용 한도와 각각 비교합니다. 예정 스케줄 생성과 별개이며, 이 검증 없이도 예정 생성과 조회는 할 수 있습니다.
          </p>
        </div>
        <div className="page-header-actions">
          {user.canProcess ? (
            <Button data-guide-feature="features.acquisition-cost.acquisition-cost-page.button-3" onClick={() => setRequesting(true)}>
              검증 요청
            </Button>
          ) : (
            <p className="field-helper">검증 요청은 처리 권한이 있는 계정만 할 수 있습니다.</p>
          )}
        </div>
      </header>
      <FilterBar
        icons
        onSubmit={() => patch({ contractNo })}
        onReset={reset}
      >
        <Field variant="filter" label="판정">
          <select
            data-guide-feature="features.acquisition-cost.acquisition-cost-page.select-2"
            className="select-control"
            value={criteria.resultStatus}
            onChange={(event) => patch({ resultStatus: event.target.value })}
          >
            <option value="">전체</option>
            {statuses.map((status) => (
              <option key={status.value} value={status.value}>
                {status.label}
              </option>
            ))}
          </select>
        </Field>
        <Field variant="filter" label="지급단계">
          <select
            data-guide-feature="features.acquisition-cost.acquisition-cost-page.select-3"
            className="select-control"
            value={criteria.paymentStage}
            onChange={(event) => patch({ paymentStage: event.target.value })}
          >
            <option value="">전체</option>
            <option value="GA_TO_FC">GA→설계사</option>
            <option value="INSURER_TO_GA">원수사→GA</option>
          </select>
        </Field>
        <Field variant="filter" label="보험회사">
          <select
            data-guide-feature="features.acquisition-cost.acquisition-cost-page.select-4"
            className="select-control"
            value={criteria.insurerId}
            disabled={insurers.isLoading || Boolean(insurers.error)}
            onChange={(event) => patch({ insurerId: event.target.value })}
          >
            <option value="">{insurers.error ? '보험회사 조회 실패' : '전체'}</option>
            {insurers.data?.map((row) => (
              <option key={row.insurerId} value={row.insurerId} disabled={row.activeYn === false}>
                {row.insurerCode}, {row.insurerName}
              </option>
            ))}
          </select>
        </Field>
        <Field variant="filter" label="계약번호">
          <input
            data-guide-feature="features.acquisition-cost.acquisition-cost-page.input"
            type="search"
            placeholder="계약번호 입력"
            value={contractNo}
            onChange={(event) => setDraft({ source: criteria.contractNo, value: event.target.value })}
          />
        </Field>
      </FilterBar>
      <ResizableSplit
        className="acq-content-grid"
        storageKey="acquisition-cost-workspace"
        label="검증 결과 목록과 상세 너비"
        defaultRatio={58}
      >
        <section
          data-guide-feature="features.acquisition-cost.acquisition-cost-page.section"
          className="surface acq-panel"
          aria-labelledby="acq-list-title"
        >
          <ListHeader total={query.error ? undefined : (query.data?.totalElements ?? 0)} pageSize={20}>
            <h2 className="surface-title" id="acq-list-title">
              검증 결과
            </h2>
          </ListHeader>
          {busy ? (
            <div className="empty-state">
              <QueryState loading />
            </div>
          ) : query.error ? (
            <AcquisitionFailure error={query.error} retry={() => void query.refetch()} />
          ) : (
            <DataTable
              caption="조회 조건에 해당하는 계약체결비용 한도 검증 결과"
              className="acq-list-table"
              columns={columns}
              {...sortBinding(criteria.sort, ACQUISITION_SORT_KEYS, (sort) => patch({ sort }))}
              rows={rows}
              rowKey={(row) => String(row.acquisitionCostCheckId)}
              selectedKeys={selected ? [String(selected.acquisitionCostCheckId)] : []}
              onRowActivate={(row) => {
                if (row.acquisitionCostCheckId != null) setSelection({ source, id: row.acquisitionCostCheckId })
              }}
              rowLabel={(row) => `계약 ${row.contractNo} 결과 보기`}
              emptyMessage="조건에 맞는 검증 결과가 없습니다."
            />
          )}
          {!query.error && (
            <ListPagination
              label="계약체결비용 한도 검증 목록 페이지"
              page={query.data?.page ?? criteria.page}
              totalPages={query.data?.totalPages ?? 0}
              onPageChange={(page) => patch({ page }, false)}
            />
          )}
        </section>
        <section
          data-guide-feature="features.acquisition-cost.acquisition-cost-page.section-2"
          className="surface acq-panel"
          aria-labelledby="acq-detail-title"
        >
          <header className="surface-header">
            <h2 className="surface-title" id="acq-detail-title">
              결과 상세
            </h2>
          </header>
          <div className="surface-body">
            {query.error ? null : selected ? (
              <AcquisitionCostResult check={selected} />
            ) : (
              <p className="empty-state">{busy ? '결과를 불러오는 중입니다.' : '볼 결과가 없습니다.'}</p>
            )}
          </div>
        </section>
      </ResizableSplit>
      {requesting && (
        <RequestCheckModal
          onClose={() => setRequesting(false)}
          onDone={(check) => {
            setRequesting(false)
            if (check.acquisitionCostCheckId != null) setSelection({ source, id: check.acquisitionCostCheckId })
            toast(`검증을 저장했습니다. 판정: ${check.resultStatusLabel}`, 'success')
          }}
        />
      )}
    </div>
  )
}
