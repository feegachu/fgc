import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { Link } from 'react-router'
import { ApiError } from '../../lib/api/client'
import { errorText } from '../../lib/format'
import { recordContractCheck } from './api'
import type { AgentHome, PriorityContract } from './api'

const REASON_LABEL: Record<string, string> = {
  FOLLOW_UP: '후속 확인일 도래',
  STATUS_EVENT_UNCONFIRMED: '상태 사건 미확인',
  HOLD_UNRESOLVED: '보류 미해결',
}

/** 확인할 계약(FUN-086). 확인 기록은 서버에 쌓이고, 같은 사건은 다시 뜨지 않는다. */
export function PriorityContracts({ home }: { home: AgentHome }) {
  const items = home.priorityContracts ?? []
  const total = home.priorityContractTotal ?? items.length
  const [open, setOpen] = useState<number | null>(null)
  return (
    <section className="ah-panel ah-priority" aria-label="확인할 계약">
      <div className="ah-panel-head">
        <div>
          <h2 className="ah-panel-title">확인할 계약</h2>
          <p className="ah-panel-sub">
            {total === 0 ? '지금 확인할 계약이 없어요' : `총 ${total}건${total > items.length ? ` · 상위 ${items.length}건 표시` : ''}`}
          </p>
        </div>
      </div>
      {items.length > 0 && (
        <ul className="ah-priority-list">
          {items.map((item) => (
            <li key={item.contractId} className="ah-priority-item">
              <div className="ah-priority-main">
                <Link to={`/contracts/${item.contractId}`}>{item.contractNo}</Link>
                <span className="ah-priority-meta">
                  {item.insurerName} · {item.productName} · {item.currentStatus}
                  {item.elapsedDays != null && ` · 마지막 사건 ${item.elapsedDays}일 경과`}
                </span>
                <span className="ah-priority-reasons">
                  {(item.reasonCodes ?? []).map((code) => (
                    <span key={code} className="ah-badge warn">
                      {REASON_LABEL[code] ?? code}
                      {code === 'FOLLOW_UP' && item.nextCheckDate ? ` (${item.nextCheckDate})` : ''}
                    </span>
                  ))}
                </span>
              </div>
              <button
                type="button"
                className="button button-secondary"
                aria-expanded={open === item.contractId}
                onClick={() => setOpen(open === item.contractId ? null : (item.contractId ?? null))}
              >
                확인 기록
              </button>
              {open === item.contractId && <CheckForm item={item} onDone={() => setOpen(null)} />}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function CheckForm({ item, onDone }: { item: PriorityContract; onDone: () => void }) {
  const client = useQueryClient()
  // 같은 제출을 다시 보내도 한 번만 기록되도록 폼을 열 때 키를 한 번 만든다.
  const key = useRef(crypto.randomUUID())
  const [result, setResult] = useState<'CLOSED' | 'FOLLOW_UP'>('CLOSED')
  const [next, setNext] = useState('')
  const [memo, setMemo] = useState('')
  const save = useMutation({
    mutationFn: () =>
      recordContractCheck(
        {
          contractId: item.contractId ?? 0,
          result,
          nextCheckDate: result === 'FOLLOW_UP' ? next : undefined,
          memo: memo.trim() || undefined,
          expectedContextVersion: item.contextVersion ?? '',
        },
        key.current,
      ),
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: ['agent-home', 'home'] })
      onDone()
    },
    onError: (error) => {
      // 다른 변경이 먼저 있었다면 최신 상태를 다시 받아 새 맥락으로 기록하게 한다.
      if (error instanceof ApiError && error.status === 409) void client.invalidateQueries({ queryKey: ['agent-home', 'home'] })
    },
    meta: { errorToast: false },
  })
  const invalid = result === 'FOLLOW_UP' && !next
  return (
    <form
      className="ah-check-form"
      aria-label={`${item.contractNo} 확인 기록`}
      onSubmit={(event) => {
        event.preventDefault()
        if (!invalid) save.mutate()
      }}
    >
      <div role="group" aria-label="확인 결과" className="ah-check-result">
        <label>
          <input type="radio" name={`r-${item.contractId}`} checked={result === 'CLOSED'} onChange={() => setResult('CLOSED')} /> 종결
        </label>
        <label>
          <input type="radio" name={`r-${item.contractId}`} checked={result === 'FOLLOW_UP'} onChange={() => setResult('FOLLOW_UP')} /> 후속 확인 필요
        </label>
      </div>
      {result === 'FOLLOW_UP' && (
        <label className="ah-check-field">
          다음 확인일
          <input type="date" value={next} onChange={(event) => setNext(event.target.value)} required />
        </label>
      )}
      <label className="ah-check-field">
        메모
        <textarea value={memo} maxLength={1000} rows={2} onChange={(event) => setMemo(event.target.value)} />
      </label>
      {save.isError && (
        <p role="alert" className="ah-check-error">
          {save.error instanceof ApiError && save.error.status === 409
            ? '그 사이 계약 상태가 바뀌었어요. 목록을 새로 불러왔으니 다시 확인해 주세요.'
            : errorText(save.error as Error, '확인 기록을 저장하지 못했습니다.')}
        </p>
      )}
      <button type="submit" className="button" disabled={save.isPending || invalid}>
        기록
      </button>
    </form>
  )
}
