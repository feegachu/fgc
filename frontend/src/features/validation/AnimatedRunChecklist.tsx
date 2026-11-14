import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { Button } from '../../components/Button'
import { StatusBadge } from '../../components/StatusBadge'
import type { FinalizeChecklist } from './api'
import { safeInternalLink } from './utils'

const CONDITIONS = [
  { label: '검증 실행이 계산완료 상태인가', step: 8 },
  { label: '차변과 대변이 다른 분개가 0건인가', step: 6 },
  { label: '긴급 미처리 예외가 0건인가', step: 8 },
  { label: '정책 없음 · 정책 중복이 0건인가', step: 3 },
  { label: '귀속합계 오류가 0건인가', step: 7 },
  { label: '계약별 상세 합계와 실행 요약 합계가 같은가', step: 8 },
  { label: '같은 검증월의 가장 최근 실행인가', step: 8 },
  { label: '같은 검증월에 이미 확정된 실행이 없는가', step: 8 },
]

export function AnimatedRunChecklist({ completed, data, error, retry, onPresented }: {
  completed: number; data?: FinalizeChecklist; error?: string; retry: () => void; onPresented?: () => void
}) {
  const [revealed, setRevealed] = useState<number[]>([])
  const eligible = CONDITIONS.map((_item, index) => index + 1)
    .filter(no => completed >= CONDITIONS[no - 1].step && data?.conditions?.some(item => item.no === no))
  const next = eligible.find(no => !revealed.includes(no))
  useEffect(() => {
    if (next === undefined) return
    const timer = window.setTimeout(() => setRevealed(previous => [...previous, next]),
      window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches ? 0 : 520)
    return () => window.clearTimeout(timer)
  }, [next])
  const allShown = CONDITIONS.every((_item, index) => eligible.includes(index + 1) && revealed.includes(index + 1))
  useEffect(() => {
    if (!allShown || !onPresented) return
    // The last revealed check still needs to finish drawing before completion is announced.
    const timer = window.setTimeout(onPresented, window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches ? 0 : 520)
    return () => window.clearTimeout(timer)
  }, [allShown, onPresented])
  return <section data-guide-feature="features.validation.validation-run-detail-page.section-7" className="surface" aria-labelledby="vrun-checklist-title" data-guide-checklist-ready={allShown}>
    <header className="surface-header">
      <h2 className="surface-title" id="vrun-checklist-title">확정 전 8단계 점검</h2>
      {allShown ? <StatusBadge tone={data?.passed ? 'success' : 'error'}>{data?.passed ? '8개 조건 모두 통과' : '미충족 조건 있음'}</StatusBadge>
        : <span className="vrun-card-note">{error ? '조회 실패' : '확인 대기'}</span>}
    </header>
    <div className="surface-body">
      {error && <p className="vrun-inline-state is-error" role="alert">{error}<Button data-guide-feature="features.animated-run-checklist.button-1" variant="secondary" onClick={retry}>다시 시도</Button></p>}
      <div className="vrun-checklist">
        {CONDITIONS.map((item, index) => {
          const no = index + 1
          const result = eligible.includes(no) && revealed.includes(no) ? data?.conditions?.find(condition => condition.no === no) : undefined
          const to = result && !result.passed ? safeInternalLink(result.linkUrl) : null
          return <div className="vrun-checklist-item" data-guide-condition={no} key={no}>
            <span key={result ? String(result.passed) : 'pending'} className={`vrun-condition-icon ${result ? result.passed ? 'is-pass' : 'is-fail' : 'is-pending'}`} aria-hidden="true">
              {!result ? <svg className="vrun-pending-dots" viewBox="0 0 24 24" fill="currentColor"><circle cx="5" cy="12" r="1.8" /><circle cx="12" cy="12" r="1.8" /><circle cx="19" cy="12" r="1.8" /></svg> : result.passed ? <svg className="vrun-check-mark" viewBox="0 0 24 24"><path d="m5 12 4 4L19 6" /></svg>
                : <svg className="vrun-condition-cross" viewBox="0 0 24 24" fill="none"><path d="m7 7 10 10M17 7 7 17" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" /></svg>}
            </span>
            <div className="vrun-checklist-label">{no}. {result?.label ?? item.label}
              <span className={result && !result.passed ? 'vrun-condition-fail' : undefined}> · {result ? result.passed ? '통과' : `미충족 ${result.count}건` : '확인 대기'}</span>
            </div>
            <div className="vrun-checklist-result">{to && <Link className="vrun-text-link" data-guide-feature="features.validation.validation-run-detail-page.link-3" to={to} aria-label={`${result?.label} 미충족 건 바로가기`}>바로가기</Link>}</div>
          </div>
        })}
      </div>
    </div>
  </section>
}
