import { useEffect, useRef } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router'
import { Button } from '../../components/Button'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { StatusBadge } from '../../components/StatusBadge'
import type { StatusTone } from '../../components/StatusBadge'
import { date, dateTime, errorText, int, isNegative, won } from '../../lib/format'
import { toast } from '../../stores/toasts'
import { useWorkspaceStore } from '../../stores/workspace'
import { ScreenHelp } from '../assistant/guide/ScreenHelp'
import { usePermission } from '../auth/useAuth'
import {
  bankLinkQuery,
  bankReconciliationQuery,
  completeBankLink,
  goToBank,
  requestBankAuthorizeUrl,
  runBankReconciliation,
} from './api'
import type { BankReconciliationItem } from './api'
import './bank-reconciliation.css'

/*
 * FGC-UI-BANK-W01 지급 이체 대사 (FUN-090, IF-API-95~99).
 * 확정된 정산월의 설계사별 확정 지급 순액과, 연결한 은행 계좌에서 실제로 나간 출금을 맞춰 본다. 은행에서는 조회만 한다.
 */
const results: Record<string, { label: string; tone: StatusTone }> = {
  MATCHED: { label: '일치', tone: 'success' },
  AMOUNT_MISMATCH: { label: '금액 다름', tone: 'error' },
  NOT_TRANSFERRED: { label: '이체 없음', tone: 'warning' },
  UNKNOWN_WITHDRAWAL: { label: '확인 안 된 출금', tone: 'review' },
}
const dash = (value: unknown) => (value == null || value === '' ? '-' : String(value))
/** 은행이 돌려보내며 주소에 붙이는 값. 처리한 뒤 주소에서 지운다. */
const BANK_RETURN_PARAMS = ['code', 'state', 'scope', 'client_info', 'error', 'error_description']

const columns: TableColumn<BankReconciliationItem>[] = [
  {
    key: 'result',
    label: '결과',
    width: '9rem',
    render: (row) => {
      const result = results[row.result ?? '']
      return (
        <StatusBadge tone={result?.tone} optionCount={Object.keys(results).length}>
          {result?.label ?? dash(row.result)}
        </StatusBadge>
      )
    },
  },
  { key: 'agentName', label: '설계사', width: '7rem', render: (row) => dash(row.agentName) },
  { key: 'agentCode', label: '설계사 코드', width: '7.5rem', render: (row) => dash(row.agentCode) },
  { key: 'expectedAmount', label: '확정 지급 순액', width: '9rem', align: 'number', render: (row) => won(row.expectedAmount) },
  { key: 'bankAmount', label: '은행 출금액', width: '9rem', align: 'number', render: (row) => won(row.bankAmount) },
  {
    key: 'differenceAmount',
    label: '차이',
    width: '8rem',
    align: 'number',
    cellClassName: 'bank-reconciliation-difference',
    render: (row) => (
      <span className={isNegative(row.differenceAmount) ? 'is-negative-amount' : undefined}>{won(row.differenceAmount)}</span>
    ),
  },
  { key: 'bankTranDate', label: '출금일', width: '7rem', render: (row) => date(row.bankTranDate) },
  { key: 'printContent', label: '통장 표시', width: '10rem', render: (row) => dash(row.printContent) },
]

export default function BankReconciliationPage() {
  const month = useWorkspaceStore((state) => state.month)
  const canProcess = usePermission('canProcess')
  const client = useQueryClient()
  const [searchParams, setSearchParams] = useSearchParams()
  const link = useQuery(bankLinkQuery())
  const reconciliation = useQuery({ ...bankReconciliationQuery(month ?? ''), enabled: month !== null })

  const connect = useMutation({
    mutationFn: requestBankAuthorizeUrl,
    meta: { errorToast: false },
    onSuccess: (data) => {
      if (data.authorizeUrl) goToBank(data.authorizeUrl)
    },
  })
  const complete = useMutation({
    mutationFn: completeBankLink,
    meta: { errorToast: false },
    onSuccess: (data) => {
      client.setQueryData(bankLinkQuery().queryKey, data)
      toast('은행 계좌를 연결했습니다.', 'success')
    },
  })
  const run = useMutation({
    mutationFn: () => runBankReconciliation(month ?? ''),
    meta: { errorToast: false },
    onSuccess: (data) => client.setQueryData(bankReconciliationQuery(month ?? '').queryKey, data),
  })

  // 은행 동의 화면에서 돌아오면 받은 값을 한 번만 처리한다. 새로 고침으로 다시 보내지 않게 주소에서는 지운다.
  // 셸이 정산월을 주소에 붙이면서 이 값을 되살릴 수 있어, 주소에 보일 때마다 지운다.
  const returned = useRef(false)
  const completeLink = complete.mutate
  useEffect(() => {
    if (!BANK_RETURN_PARAMS.some((name) => searchParams.has(name))) return
    if (!returned.current) {
      returned.current = true
      const code = searchParams.get('code')
      const state = searchParams.get('state')
      if (code && state) completeLink({ code, state })
      else toast('은행 연결을 취소했습니다.', 'info')
    }
    const next = new URLSearchParams(searchParams)
    for (const name of BANK_RETURN_PARAMS) next.delete(name)
    setSearchParams(next, { replace: true })
  }, [searchParams, setSearchParams, completeLink])

  const account = link.data
  const result = reconciliation.data
  const linkError = link.error ?? connect.error ?? complete.error
  const resultError = reconciliation.error ?? run.error
  return (
    <div className="bank-reconciliation-page">
      <header className="page-header">
        {canProcess ? <ScreenHelp screenId="BANK-W01" /> : <ScreenHelp screenId="BANK-W01" variant="reader" />}
        <div className="page-header-copy">
          <h1 className="page-title">지급 이체 대사</h1>
        </div>
      </header>

      <section
        data-guide-feature="features.bank-reconciliation.bank-reconciliation-page.section"
        data-guide-target="bank.link"
        className="surface"
        aria-labelledby="bank-link-title"
      >
        <header className="surface-header">
          <h2 id="bank-link-title" className="surface-title">
            은행 연결
          </h2>
          {canProcess && (
            <Button
              data-guide-feature="features.bank-reconciliation.bank-reconciliation-page.button"
              variant="secondary"
              disabled={connect.isPending || complete.isPending}
              onClick={() => connect.mutate()}
            >
              {account?.linked ? '다시 연결' : '은행 연결'}
            </Button>
          )}
        </header>
        <div className="surface-body bank-reconciliation-link">
          {account?.linked ? (
            <>
              <p>
                <strong>
                  {account.bankName} {account.accountNumMasked}
                </strong>{' '}
                <span>{account.accountHolderName}</span>
              </p>
              {!account.connected && <p className="field-helper">다시 연결해야 은행 거래내역을 가져올 수 있습니다.</p>}
            </>
          ) : (
            <p>{link.isPending ? '불러오는 중입니다.' : '연결한 계좌가 없습니다.'}</p>
          )}
          {account?.mock && <p className="field-helper">실제 은행이 아니라 모의 은행입니다.</p>}
          {linkError && (
            <p className="field-error" role="alert">
              {errorText(linkError)}
            </p>
          )}
        </div>
      </section>

      <section
        data-guide-feature="features.bank-reconciliation.bank-reconciliation-page.section-2"
        data-guide-target="bank.result"
        className="surface"
        aria-labelledby="bank-result-title"
      >
        <header className="surface-header">
          <h2 id="bank-result-title" className="surface-title">
            대사 결과 <span className="tabular-nums">{month}</span>
          </h2>
          {canProcess && (
            <Button
              data-guide-feature="features.bank-reconciliation.bank-reconciliation-page.button-2"
              variant="primary"
              disabled={run.isPending || month === null}
              onClick={() => run.mutate()}
            >
              은행 거래내역으로 대사
            </Button>
          )}
        </header>
        <div className="surface-body bank-reconciliation-result">
          {resultError && (
            <p className="field-error" role="alert">
              {errorText(resultError)}
            </p>
          )}
          {result ? (
            <>
              <p className="bank-reconciliation-summary tabular-nums">
                일치 {int(result.matchedCount)}건, 금액 다름 {int(result.amountMismatchCount)}건, 이체 없음{' '}
                {int(result.notTransferredCount)}건, 확인 안 된 출금 {int(result.unknownWithdrawalCount)}건
              </p>
              <p className="field-helper tabular-nums">
                {date(result.inquiryFrom)}부터 {date(result.inquiryTo)}까지의 출금 {int(result.withdrawalCount)}건을 봤습니다.{' '}
                {dateTime(result.createdAt)} {result.createdBy}
              </p>
              <DataTable
                className="bank-reconciliation-table"
                caption="지급 이체 대사 결과"
                columns={columns}
                rows={result.items ?? []}
                rowKey={(row) => `${row.result}-${row.referenceCode ?? row.printContent}-${row.bankTranDate ?? ''}-${row.bankAmount}`}
              />
            </>
          ) : (
            <p>{reconciliation.isPending && month !== null ? '불러오는 중입니다.' : '아직 대사하지 않았습니다.'}</p>
          )}
        </div>
      </section>
    </div>
  )
}
