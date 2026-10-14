import { Button } from '../../components/Button'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { StatusBadge } from '../../components/StatusBadge'
import { won } from '../../lib/format'
import type { Contract } from './form-api'
import { inclusionOptions, METHODS, selectContract } from './form-model'
import type { AttributionRow } from './form-model'

export function AttributionEditor({
  rows,
  contracts,
  agentLabel,
  amount,
  insurer,
  newcomer,
  disabled,
  error,
  onChange,
  onAdd,
}: {
  rows: AttributionRow[]
  contracts: Contract[]
  agentLabel: string
  amount: string
  insurer: boolean
  newcomer: boolean
  disabled: boolean
  error?: { field: string; message: string }
  onChange: (rows: AttributionRow[]) => void
  onAdd: () => void
}) {
  const sum = rows.reduce((total, row) => total + (Number(row.amount) || 0), 0)
  const difference = (Number(amount) || 0) - sum
  const patch = (row: AttributionRow, value: Partial<AttributionRow>) =>
    onChange(rows.map((current) => (current.key === row.key ? { ...row, ...value } : current)))
  const label = (row: AttributionRow, name: string) => `${rows.indexOf(row) + 1}번 귀속행 ${name}`
  const control = (row: AttributionRow, field: keyof AttributionRow, name: string) => ({
    id: `attr-${row.key}-${field}`,
    'aria-label': label(row, name),
    disabled,
    'aria-invalid': error?.field === `attr-${row.key}-${field}` || undefined,
    'aria-describedby': error?.field === `attr-${row.key}-${field}` ? 'err-attributions' : undefined,
  })
  const columns: TableColumn<AttributionRow>[] = [
    { key: 'index', label: '#', width: '2.5rem', render: (row) => rows.indexOf(row) + 1 },
    { key: 'scope', label: '귀속 범위', width: '6rem', render: () => (newcomer ? '설계사' : '계약') },
    {
      key: 'contract',
      label: '계약번호',
      width: '11rem',
      render: (row) =>
        newcomer ? (
          <span className="transaction-panel-meta">-</span>
        ) : (
          <select
            {...control(row, 'contractId', '계약번호')}
            data-contract-id
            value={row.contractId}
            onChange={(event) => patch(row, selectContract(row, event.target.value, contracts))}
          >
            <option value="">계약을 선택하세요</option>
            {contracts.map((contract) => (
              <option key={contract.contractId} value={contract.contractId}>
                {contract.contractNo} · {contract.productName}
              </option>
            ))}
          </select>
        ),
    },
    { key: 'agent', label: '설계사', width: '9rem', render: () => agentLabel },
    {
      key: 'date',
      label: '귀속일',
      width: '8rem',
      render: (row) => (
        <input
          {...control(row, 'attributionDate', '귀속일')}
          data-attr-date
          type="date"
          value={row.attributionDate}
          min={contracts.find((contract) => String(contract.contractId) === row.contractId)?.contractDate}
          onChange={(event) => patch(row, { attributionDate: event.target.value })}
        />
      ),
    },
    {
      key: 'month',
      label: '귀속월',
      width: '8rem',
      render: (row) => (
        <input
          aria-label={label(row, '귀속월')}
          data-attr-month
          readOnly
          disabled={disabled}
          value={row.attributionDate ? `${row.attributionDate.slice(0, 7)}-01` : ''}
        />
      ),
    },
    {
      key: 'amount',
      label: '귀속금액',
      width: '8rem',
      align: 'number',
      render: (row) => (
        <input
          {...control(row, 'amount', '귀속금액')}
          data-attr-amount
          type="number"
          min="0"
          step="1"
          value={row.amount}
          onChange={(event) => patch(row, { amount: event.target.value })}
        />
      ),
    },
    {
      key: 'decision',
      label: '산입 판정',
      width: '9rem',
      render: (row) => (
        <select
          {...control(row, 'decision', '산입 판정')}
          data-inclusion
          value={row.decision}
          onChange={(event) => patch(row, { decision: event.target.value })}
        >
          {inclusionOptions(insurer, newcomer).map(([value, name]) => (
            <option key={value} value={value}>
              {name}
            </option>
          ))}
        </select>
      ),
    },
    {
      key: 'method',
      label: '귀속 방법',
      width: '10rem',
      render: (row) =>
        newcomer ? (
          <span className="transaction-panel-meta" aria-label="신인 비계약 자동 귀속">
            -
          </span>
        ) : (
          <select
            {...control(row, 'attributionMethod', '귀속 방법')}
            data-attr-method
            value={row.attributionMethod}
            onChange={(event) =>
              patch(row, { attributionMethod: event.target.value as AttributionRow['attributionMethod'] })
            }
          >
            {METHODS.map(([value, name]) => (
              <option
                key={value}
                value={value}
                disabled={insurer && ['SETTLEMENT_SUPPORT_MONTHLY', 'FIRST_CONTRACT_CARRY_FORWARD'].includes(value)}
              >
                {name}
              </option>
            ))}
          </select>
        ),
    },
    {
      key: 'policy',
      label: '배부정책',
      width: '9rem',
      render: (row) => (
        <input
          {...control(row, 'allocationBasis', '배부정책')}
          data-allocation-basis
          value={row.allocationBasis}
          onChange={(event) => patch(row, { allocationBasis: event.target.value })}
        />
      ),
    },
    {
      key: 'evidence',
      label: '증빙',
      width: '13rem',
      render: (row) => (
        <input
          {...control(row, 'evidenceRef', '증빙')}
          data-attr-evidence
          value={row.evidenceRef}
          placeholder={newcomer ? '신인 지원 증빙을 입력하세요' : '증빙 입력'}
          onChange={(event) => patch(row, { evidenceRef: event.target.value })}
        />
      ),
    },
    {
      key: 'remove',
      label: <span className="visually-hidden">행 삭제</span>,
      width: '3.5rem',
      render: (row) => (
        <Button
          variant="ghost"
          disabled={disabled}
          aria-label={`${rows.indexOf(row) + 1}번 귀속행 삭제`}
          onClick={() => onChange(rows.filter((current) => current.key !== row.key))}
        >
          삭제
        </Button>
      ),
    },
  ]
  return (
    <section className="surface transaction-attribution-panel" aria-labelledby="transaction-attribution-title">
      <header className="surface-header transaction-surface-header">
        <h2 className="surface-title" id="transaction-attribution-title">
          ② 귀속행 — 이 돈이 어느 계약 몫인가
        </h2>
        <Button
          variant="secondary"
          className="transaction-add-attribution"
          id="btn-add-attr"
          disabled={disabled}
          onClick={onAdd}
        >
          <span className="material-symbols-rounded" aria-hidden="true">
            add
          </span>{' '}
          귀속행 추가
        </Button>
      </header>
      <DataTable
        caption="지급 건에 연결할 계약별 귀속행"
        className="transaction-attribution-table"
        viewportClassName="transaction-attribution-viewport"
        bodyId="attr-body"
        columns={columns}
        rows={rows}
        rowKey={(row) => String(row.key)}
        emptyMessage="귀속행을 추가하세요."
        footer={
          <tr>
            <td colSpan={6}>귀속금액 합계</td>
            <td className="is-number">
              <span className="tabular-nums" id="attr-sum">
                {won(sum)}
              </span>
            </td>
            <td colSpan={5}>
              <span id="attr-diff">
                <StatusBadge tone={difference === 0 ? 'success' : 'error'}>
                  {difference === 0 ? '일치' : `불일치 · 차액 ${won(difference)}`}
                </StatusBadge>
              </span>
            </td>
          </tr>
        }
      />
      {error && (error.field.startsWith('attr-') || error.field === 'attributions') && (
        <p className="field-error transaction-attribution-error" id="err-attributions" role="alert">
          {error.message}
        </p>
      )}
    </section>
  )
}
