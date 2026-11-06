import { useState, type ReactNode } from 'react'
import { int, won } from '../../lib/format'
import type { StatementAgentOverview } from './api'

export function StatementExplorer({
  rows,
  selectedId,
  onSelect,
  viewControl,
}: {
  viewControl: ReactNode
  rows: StatementAgentOverview[]
  selectedId: string | null
  onSelect: (id: string) => void
}) {
  const [branch, setBranch] = useState<number | null>(null)
  const [closed, setClosed] = useState<number[]>([])
  const branches = [...new Map(rows.map((row) => [row.branchId, row.branchName])).entries()]
  const selected = rows.find((row) => String(row.agentId) === selectedId)
  const activeBranch = branches.some(([id]) => id === branch) ? branch : (selected?.branchId ?? branches[0]?.[0])
  const visible = rows.filter((row) => row.branchId === activeBranch)
  return (
    <section className="surface ast-explorer" data-guide-target="statement.results" data-guide-feature="features.agent-statement.agent-statement-page.list-panel" aria-label="지점 및 설계사 명세 탐색">
      <div className="ast-explorer-columns">
        <nav className="ast-branch-tree" aria-label="지점과 설계사 목록">
          {branches.map(([id, name]) => (
            <div key={id}>
              <button
                className={`ast-branch ${activeBranch === id ? 'is-active' : ''}`}
                aria-expanded={activeBranch === id && !closed.includes(id)}
                onClick={() => {
                  setBranch(id)
                  setClosed((previous) =>
                    activeBranch === id && !previous.includes(id)
                      ? [...previous, id]
                      : previous.filter((value) => value !== id),
                  )
                }}
              >
                <span className="material-symbols-rounded" aria-hidden="true">
                  {activeBranch === id && !closed.includes(id) ? 'keyboard_arrow_down' : 'keyboard_double_arrow_right'}
                </span>
                <span className="material-symbols-rounded" aria-hidden="true">
                  folder
                </span>
                <strong>{name}</strong>
                <small>{rows.filter((row) => row.branchId === id).length}명</small>
              </button>
              {activeBranch === id && !closed.includes(id) && (
                <ul>
                  {visible.map((row) => (
                    <li key={row.agentId}>
                      <button
                        aria-current={String(row.agentId) === selectedId ? 'true' : undefined}
                        onClick={() => onSelect(String(row.agentId))}
                      >
                        <span className="material-symbols-rounded" aria-hidden="true">
                          account_circle
                        </span>
                        {row.agentName}
                        <small>{int(row.contractCount)}건</small>
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          ))}
        </nav>
        <div className="ast-agent-area">
          <div className="ast-agent-area-heading">
            <h3>
              {branches.find(([id]) => id === activeBranch)?.[1]}{' '}
              <span className="ast-muted">설계사 {visible.length}명</span>
            </h3>
            {viewControl}
          </div>
          <div className="ast-agent-cards">
            {visible.map((row) => (
              <button
                className={`ast-agent-card ${String(row.agentId) === selectedId ? 'is-active' : ''}`}
                key={row.agentId}
                aria-pressed={String(row.agentId) === selectedId}
                onClick={() => onSelect(String(row.agentId))}
              >
                <span className="ast-agent-card-head">
                  <span className="material-symbols-rounded" aria-hidden="true">
                    account_circle
                  </span>
                  <strong>{row.agentName}</strong>
                  <span className="material-symbols-rounded" aria-hidden="true">
                    arrow_forward
                  </span>
                </span>
                <span className="ast-muted">
                  {row.agentCode} · {row.organizationName}
                </span>
                <span className="ast-agent-card-metric">
                  <span>명세 연결 계약</span>
                  <strong>{int(row.contractCount)}건</strong>
                </span>
                <span className="ast-agent-card-metric">
                  <span>지급 확정</span>
                  <strong>{won(row.paymentAmount)}</strong>
                </span>
                <span className="ast-agent-card-extra">
                  보류 {won(row.heldAmount)} · 조정 {won(row.adjustmentAmount)}
                  <br />
                  환수 계산 {won(row.clawbackAmount)}
                  {row.missingAmountCount > 0 && ` · 금액 미확인 ${row.missingAmountCount}건`}
                </span>
              </button>
            ))}
          </div>
        </div>
      </div>
    </section>
  )
}

export function StatementViewSwitch({
  value,
  onChange,
}: {
  value: 'tree' | 'table'
  onChange: (value: 'tree' | 'table') => void
}) {
  return (
    <div className={`ast-view-switch ast-view-${value}`} role="group" aria-label="명세 보기 방식">
      <span className="ast-view-slider" aria-hidden="true" />
      <button type="button" aria-label="트리 보기" aria-pressed={value === 'tree'} onClick={() => onChange('tree')} title="트리 보기">
        <span className="material-symbols-rounded" aria-hidden="true">
          folder
        </span>
      </button>
      <button type="button" aria-label="표 보기" aria-pressed={value === 'table'} onClick={() => onChange('table')} title="표 보기">
        <span className="material-symbols-rounded" aria-hidden="true">
          table_chart
        </span>
      </button>
    </div>
  )
}
