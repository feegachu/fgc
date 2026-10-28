import { useMutation, useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import type { CSSProperties, KeyboardEvent, ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { compareCategoriesQuery, compareExplainQuery, rankCompare } from './api'
import type { CompareCategory, CompareEvidence, CompareItem, CompareRanking } from './api'
import { FilterPanel } from './FilterPanel'
import { EMPTY_FILTERS, toFilters } from './filters'
import type { FilterDraft } from './filters'
import { FAQ, capBadge, followUpAnswer, followUpQuestion, gradeLabel, heroWhy, won } from './format'
import type { FollowUpKind } from './format'
import './chatbot.css'

const STEPS = ['카테고리 선택', '상품 선별', '비교 결과·근거']
const CHECKS = ['내 한도 확인', '정책 수수료율 확인', '환수 규칙 확인', '협회 등급 확인']
/** 계산이 빨라도 화면이 깜빡이지 않도록 2단계는 캐릭터가 한 바퀴 도는 시간 동안 보여 준다. chatbot.css 의 cb-turn 주기(5.5s)와 같아야 한다. */
const SPIN_MS = 5500
const CHECK_MS = SPIN_MS / CHECKS.length
const IMG = (name: string) => `${import.meta.env.BASE_URL}images/brand/chatbot-${name}.png`

function Mascot({ name, size = 101 }: { name: 'base' | 'search' | 'sum' | 'sorry'; size?: number }) {
  // 답변(3단계)은 들고 있는 막대만 따로 움직이도록 몸통과 막대를 두 겹으로 그린다.
  if (name === 'sum') {
    return (
      <span className="cb-mascot cb-pointer" style={{ width: size, height: size }} aria-hidden="true">
        <img src={IMG('sum-base')} alt="" width={size} height={size} />
        <img className="cb-stick" src={IMG('sum-stick')} alt="" width={size} height={size} />
      </span>
    )
  }
  return (
    <img
      className={`cb-mascot${name === 'search' ? ' cb-spin' : ''}`}
      src={IMG(name)}
      alt=""
      width={size}
      height={size}
      aria-hidden="true"
    />
  )
}

function Bot({
  avatar = 'base',
  name = '피코 AI',
  children,
}: {
  avatar?: 'base' | 'search' | 'sum' | 'sorry'
  name?: string
  children: ReactNode
}) {
  return (
    <div className="cb-bot">
      <Mascot name={avatar} />
      <div className="cb-bot-msg">
        <div className="cb-name">{name}</div>
        {children}
      </div>
    </div>
  )
}

function User({ text }: { text: string }) {
  return (
    <div className="cb-user">
      <div className="cb-bubble-user">{text}</div>
    </div>
  )
}

const FOCUSABLE = 'button:not([disabled]), [href], [tabindex]:not([tabindex="-1"])'

/** 상품 비교·참고 순위 챗봇 모달(FUN-079). 입력은 카테고리 버튼뿐이고 순위·금액·근거는 서버 값을 그대로 보여 준다. */
export function ChatbotModal({
  onClose,
  need,
}: {
  onClose: () => void
  need: { near: number; review: number } | null
}) {
  const categories = useQuery(compareCategoriesQuery())
  const rank = useMutation({ mutationFn: rankCompare })
  const [category, setCategory] = useState<CompareCategory | null>(null)
  const [progress, setProgress] = useState(0)
  const [selected, setSelected] = useState<CompareItem | null>(null)
  const [faq, setFaq] = useState<string | null>(null)
  const [filters, setFilters] = useState<FilterDraft>(EMPTY_FILTERS)
  // 3단계 하단 질문 버튼을 누르면 입력창에 들어가고, 보내기를 눌러야 답변을 받거나 1단계로 돌아간다.
  const [draft, setDraft] = useState<{ kind: FollowUpKind | 'restart'; text: string } | null>(null)
  const [asked, setAsked] = useState<{ kind: FollowUpKind; question: string; answer: string }[]>([])
  const dialog = useRef<HTMLDivElement>(null)
  const thread = useRef<HTMLDivElement>(null)
  const evidenceTrigger = useRef<HTMLElement | null>(null)

  const ranking = rank.data ?? null
  // 결과 말풍선 시각은 결과가 바뀔 때만 새로 잡는다(렌더마다 바뀌지 않게).
  // 화면은 선택 → 선별(서버 응답 + 최소 표시 시간) → 결과 순서로 파생한다.
  const view: 1 | 2 | 3 = !category ? 1 : rank.isSuccess && progress >= CHECKS.length ? 3 : 2
  const explain = useQuery({ ...compareExplainQuery(view === 3 ? (ranking?.rankResultId ?? null) : null) })

  useEffect(() => {
    if (view !== 2 || rank.isError) return
    const timer = setInterval(() => setProgress((p) => Math.min(CHECKS.length, p + 1)), CHECK_MS)
    return () => clearInterval(timer)
  }, [view, rank.isError])
  useEffect(() => {
    if (thread.current) thread.current.scrollTop = 0
  }, [view])
  // 질문에 답이 붙으면 새 답변이 보이도록 대화 맨 아래로 내린다.
  useEffect(() => {
    if (asked.length > 0 && thread.current) thread.current.scrollTop = thread.current.scrollHeight
  }, [asked.length])
  // 서랍을 닫으면 포커스를 근거 버튼으로 돌려 Esc·Tab 이 모달 안에서 계속 동작하게 한다.
  useEffect(() => {
    if (!selected) evidenceTrigger.current?.focus({ preventScroll: true })
  }, [selected])
  useEffect(() => {
    dialog.current?.querySelector<HTMLElement>('.cb-cat')?.focus({ preventScroll: true })
  }, [categories.isSuccess])

  const pick = (next: CompareCategory) => {
    setCategory(next)
    setProgress(0)
    setSelected(null)
    setDraft(null)
    setAsked([])
    rank.reset()
    rank.mutate({ categoryCode: next.code, filters: toFilters(filters) })
  }
  const send = () => {
    if (!draft) return
    if (draft.kind === 'restart') {
      restart()
      return
    }
    if (ranking && ranking.items.length > 0) {
      setAsked((list) => [...list, { kind: draft.kind as FollowUpKind, question: draft.text, answer: followUpAnswer(draft.kind as FollowUpKind, ranking.items) }])
    }
    setDraft(null)
  }
  const openEvidence = (item: CompareItem) => {
    evidenceTrigger.current = document.activeElement as HTMLElement | null
    setSelected(item)
  }
  const restart = () => {
    setCategory(null)
    setSelected(null)
    setDraft(null)
    setAsked([])
    setProgress(0)
    rank.reset()
  }
  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.stopPropagation()
      if (selected) setSelected(null)
      else onClose()
      return
    }
    if (event.key !== 'Tab') return
    const nodes = Array.from(dialog.current?.querySelectorAll<HTMLElement>(FOCUSABLE) ?? []).filter(
      (n) => n.offsetParent !== null,
    )
    if (!nodes.length) return
    const first = nodes[0]
    const last = nodes[nodes.length - 1]
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }

  const hint = view === 1 ? '🔒 위 선택지에서 골라 주세요' : view === 2 ? '⏳ 상품을 선별하는 중이에요' : ''
  return createPortal(
    <div className="cb cb-overlay" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <div
        ref={dialog}
        className="cb-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="cb-title"
        tabIndex={-1}
        onKeyDown={onKeyDown}
      >
        <header className="cb-head">
          <span
            className="cb-logo"
            role="img"
            aria-label="FGC"
            style={
              { '--cb-logo': `url(${import.meta.env.BASE_URL}images/brand/logo-horizontal-white.png)` } as CSSProperties
            }
          />
          <div className="cb-head-text">
            <h2 id="cb-title">상품 추천 AI</h2>
            <p>
              {category && view > 1 ? `${category.label} · ` : ''}
              {ranking ? `자료 기준일 ${ranking.asOf}` : ''}
            </p>
          </div>
          <button type="button" className="cb-close" aria-label="닫기" onClick={onClose}>
            ×
          </button>
        </header>

        <nav className="cb-stepper" aria-label="진행 단계">
          {STEPS.map((label, index) => {
            const n = index + 1
            const state = n < view ? 'done' : n === view ? 'active' : ''
            return (
              <div key={label} className={`cb-step ${state}`} aria-current={n === view ? 'step' : undefined}>
                <span className="cb-step-num">{n < view ? '' : n}</span>
                <span className="cb-step-txt">{label}</span>
              </div>
            )
          })}
        </nav>

        <div className="cb-body">
          <div ref={thread} className="cb-thread">
            {view === 1 && (
              <StepOne
                categories={categories.data}
                loading={categories.isPending}
                failed={categories.isError}
                onRetry={() => void categories.refetch()}
                onPick={pick}
                faq={faq}
                onFaq={setFaq}
                need={need}
                filters={filters}
                onFilters={setFilters}
              />
            )}
            {view === 2 && category && (
              <>
                <User text={category.label} />
                {rank.isError ? (
                  <Bot avatar="sorry">
                    <div className="cb-card" role="alert">
                      <b>순위를 불러오지 못했어요.</b>
                      <p className="cb-muted">
                        잠시 뒤 다시 시도해 주세요. 계산은 서버에서 하며 입력하신 내용은 저장되지 않아요.
                      </p>
                      <div className="cb-row">
                        <button type="button" className="cb-btn primary" onClick={() => pick(category)}>
                          다시 시도
                        </button>
                        <button type="button" className="cb-btn" onClick={restart}>
                          처음으로
                        </button>
                      </div>
                    </div>
                  </Bot>
                ) : (
                  <Bot avatar="search">
                    <div className="cb-card" role="status" aria-live="polite">
                      <span className="cb-pill">● 근거 자료 확인 중</span>
                      <h3 className="cb-loading-title">
                        내 조건에 맞는 상품을 선별하고 있어요
                        <span className="cb-dots" aria-hidden="true">
                          <i />
                          <i />
                          <i />
                        </span>
                      </h3>
                      <p className="cb-muted">
                        내 한도, 정책 수수료율, 환수 규칙, 협회 등급을 확인해 보험사별 상품을 정렬하고 있어요.
                      </p>
                      <div className="cb-checks">
                        {CHECKS.map((label, index) => {
                          const state = index < progress ? 'done' : index === progress ? 'active' : 'pending'
                          return (
                            <div key={label} className={`cb-check ${state}`}>
                              <b>{label}</b>
                              <span>{state === 'done' ? '확인 완료' : state === 'active' ? '확인 중' : '대기'}</span>
                            </div>
                          )
                        })}
                      </div>
                    </div>
                  </Bot>
                )}
                {!rank.isError && (
                  <div className="cb-skeleton" aria-hidden="true">
                    <div className="cb-skeleton-head">후보 상품 불러오는 중</div>
                    {[0, 1, 2].map((row) => (
                      <div key={row} className="cb-skeleton-row">
                        <i style={{ width: 20 }} />
                        <i style={{ width: '60%' }} />
                        <i style={{ width: '50%' }} />
                        <i style={{ width: '50%' }} />
                      </div>
                    ))}
                  </div>
                )}
              </>
            )}
            {view === 3 && category && ranking && (
              <Result
                ranking={ranking}
                category={category}
                explain={{
                  pending: explain.isPending,
                  text: explain.data?.summary ?? null,
                  failed: explain.isError || Boolean(explain.data?.summaryError),
                  source: explain.data?.summarySource,
                }}
                onEvidence={openEvidence}
              />
            )}
            {view === 3 &&
              asked.map((qa) => (
                <div key={qa.kind} className="cb-asked">
                  <User text={qa.question} />
                  <Bot avatar="sum">
                    <div className="cb-card" role="status">
                      <span>{qa.answer}</span>
                    </div>
                  </Bot>
                </div>
              ))}
          </div>

          {view === 3 ? (
            <>
              <div className="cb-suggest" role="group" aria-label="연관 질문">
                {ranking && ranking.items.length > 0 &&
                  (['why', 'limit'] as const).map((kind) => {
                    const text = followUpQuestion(kind, ranking.items[0])
                    return (
                      <button
                        key={kind}
                        type="button"
                        className={`cb-suggest-btn${draft?.kind === kind ? ' on' : ''}`}
                        aria-pressed={draft?.kind === kind}
                        disabled={asked.some((qa) => qa.kind === kind)}
                        onClick={() => setDraft({ kind, text })}
                      >
                        {text}
                      </button>
                    )
                  })}
                <button
                  type="button"
                  className={`cb-suggest-btn${draft?.kind === 'restart' ? ' on' : ''}`}
                  aria-pressed={draft?.kind === 'restart'}
                  onClick={() => setDraft({ kind: 'restart', text: '다른 카테고리도 추천 받기' })}
                >
                  다른 카테고리도 추천 받기
                </button>
              </div>
              <div className="cb-composer">
                <div className={`cb-composer-field${draft ? ' ready' : ''}`}>{draft ? draft.text : '궁금한 점을 골라 보내기를 눌러 보세요'}</div>
                <button type="button" className={`cb-send${draft ? ' on' : ''}`} aria-label="보내기" disabled={!draft} onClick={send}>
                  ➤
                </button>
              </div>
            </>
          ) : (
            <div className="cb-composer" aria-hidden="true">
              <div className="cb-composer-field">{hint}</div>
              <span className="cb-send">➤</span>
            </div>
          )}

          {selected && ranking && (
            <Drawer item={selected} ranking={ranking} total={ranking.items.length} onClose={() => setSelected(null)} />
          )}
        </div>
      </div>
    </div>,
    document.body,
  )
}

function StepOne({
  categories,
  loading,
  failed,
  onRetry,
  onPick,
  faq,
  onFaq,
  need,
  filters,
  onFilters,
}: {
  categories?: CompareCategory[]
  loading: boolean
  failed: boolean
  onRetry: () => void
  onPick: (category: CompareCategory) => void
  faq: string | null
  onFaq: (key: string | null) => void
  need: { near: number; review: number } | null
  filters: FilterDraft
  onFilters: (next: FilterDraft) => void
}) {
  const insurers = [...new Map((categories ?? []).flatMap((c) => c.insurers ?? []).map((i) => [i.id, i])).values()].sort((a, b) =>
    a.name.localeCompare(b.name, 'ko'),
  )
  const open = FAQ.find((f) => f.key === faq)
  return (
    <>
      <Bot>
        <div className="cb-card">
          <div className="cb-greet">어떤 카테고리의 상품을 비교해 볼까요?</div>
          <p className="cb-muted">선택지만 고를 수 있어요. 고객 개인정보는 입력받지 않습니다.</p>
          {loading ? (
            <p className="cb-muted" aria-busy="true">
              카테고리를 불러오는 중이에요.
            </p>
          ) : failed ? (
            <div role="alert">
              <p className="cb-muted">카테고리를 불러오지 못했어요.</p>
              <button type="button" className="cb-btn" onClick={onRetry}>
                다시 시도
              </button>
            </div>
          ) : null}
        </div>
      </Bot>
      {!loading && !failed && (
        <div className="cb-user cb-replies">
          <div className="cb-replies-bubble">
            <FilterPanel insurers={insurers} value={filters} onChange={onFilters} />
            <div className="cb-cats" role="group" aria-label="카테고리 선택">
              {(categories ?? []).map((c) => (
                <button
                  key={c.code}
                  type="button"
                  className="cb-cat"
                  disabled={c.insurerCount === 0}
                  onClick={() => onPick(c)}
                >
                  {c.label}
                  <small>{c.insurerCount > 0 ? `${c.insurerCount}개 보험사 비교` : '판매 중인 상품이 없어요'}</small>
                </button>
              ))}
            </div>
          </div>
        </div>
      )}
      <div className="cb-quick-label">자주 묻는 질문</div>
      <div className="cb-quick" role="group" aria-label="자주 묻는 질문">
        {FAQ.map((f) => (
          <button
            key={f.key}
            type="button"
            className={`cb-faq${faq === f.key ? ' on' : ''}`}
            aria-pressed={faq === f.key}
            onClick={() => onFaq(faq === f.key ? null : f.key)}
          >
            {f.label}
          </button>
        ))}
      </div>
      {open && (
        <div className="cb-faq-card" aria-live="polite">
          <b>{open.label}</b>: {open.body}
          {open.key === 'rank' && (
            <div className="cb-formula" aria-label="받을 수 있는 수수료에서 해지 시 돌려줄 금액을 뺀 값">
              <span>받을 수 있는 수수료</span>
              <i aria-hidden="true">−</i>
              <span>해지 시 돌려줄 금액</span>
              <i aria-hidden="true">=</i>
              <span className="strong">실제로 남는 돈</span>
            </div>
          )}
        </div>
      )}
      <div className="cb-info">
        {need && (
          <section className="cb-info-card cb-limit">
            <h4>내 한도 현황</h4>
            <div className="cb-limit-stats">
              <div className="near">
                <span>1,200% 한도 근접 계약</span>
                <b>{need.near}건</b>
              </div>
              <div className="review">
                <span>검토 필요 계약</span>
                <b>{need.review}건</b>
              </div>
            </div>
            <ul className="cb-limit-notes">
              <li>적용되는 한도는 정책 기준으로 계산하며, 결과 근거에서 확인할 수 있어요.</li>
              <li>한도가 거의 찬 상품은 결과에서 경고로 알려 드려요.</li>
            </ul>
          </section>
        )}
      </div>
    </>
  )
}

const WARNING_TEXT: Record<string, (n: number, excluded: number) => string> = {
  OTHER_INSURER_LT_3: (n) => `다른 보험사 상품이 ${n}개뿐이에요(3개 이상 필요). 비교 근거로 쓰기엔 부족할 수 있어요.`,
  SOME_EXCLUDED: (_n, excluded) => `자료가 부족한 상품 ${excluded}개는 순위에서 제외했어요.`,
}

/** 순위가 비었을 때 서버 상태·경고 코드에 맞는 안내 문구. */
function emptyReason(ranking: CompareRanking, category: CompareCategory) {
  if (ranking.status === 'RULE_NOT_CONFIGURED') return '상품 순위 계산 기준이 아직 확정되지 않아 순위를 보여 드릴 수 없어요.'
  if (ranking.warnings.includes('FILTER_NO_MATCH')) return '선택한 세부 조건에 맞는 상품이 없어요.'
  if (ranking.warnings.includes('NO_BASE_PREMIUM')) return '본인 계약이 없어 기준 보험료를 정할 수 없어요.'
  if (ranking.warnings.includes('ALL_EXCLUDED')) return '비교에 필요한 자료가 부족해 순위를 만들 수 없어요.'
  return `${category.label} 후보 상품을 찾지 못했어요.`
}

/** 서버가 이번 계산에 적용했다고 돌려준 세부 조건을 사람이 읽는 문구로 바꾼다. */
function appliedFilters(ranking: CompareRanking): string[] {
  const filters = ranking.filters
  if (!filters) return []
  const lines: string[] = []
  if (filters.insurerIds.length > 0) lines.push(`보험사 ${filters.insurerIds.length}곳`)
  if (filters.excludeCapOverflow) lines.push('한도 초과 상품 제외')
  return lines
}

function Result({
  ranking,
  category,
  explain,
  onEvidence,
}: {
  ranking: CompareRanking
  category: CompareCategory
  explain: { pending: boolean; text: string | null; failed: boolean; source?: 'RULE' | 'LLM' }
  onEvidence: (item: CompareItem) => void
}) {
  const items = ranking.items
  if (items.length === 0) {
    const reason = emptyReason(ranking, category)
    return (
      <>
        <User text={category.label} />
        <Bot avatar="sorry">
          <div className="cb-card" role="status">
            <b>답변을 찾지 못했어요</b>
            <p className="cb-muted">{reason} 다른 카테고리를 골라 보세요.</p>
          </div>
        </Bot>
      </>
    )
  }
  const insurerCount = new Set(items.map((i) => i.insurer)).size
  const warnings = ranking.warnings.filter((w) => WARNING_TEXT[w])
  const top = items[0]
  const why = heroWhy(items)
  return (
    <>
      <User text={category.label} />
      <Bot avatar={explain.failed ? 'sorry' : 'sum'}>
        <div className={`cb-card cb-summary${explain.failed ? ' err' : ''}`} role="status">
          {explain.failed ? (
            <span>요약을 불러오지 못했어요(FGC-AI-001). 아래 카드와 표는 그대로 확인할 수 있어요.</span>
          ) : explain.pending ? (
            <span className="cb-muted">요약을 쓰고 있어요…</span>
          ) : (
            <>
              <span className="cb-pill">{explain.source === 'LLM' ? 'AI 요약' : '요약'}</span>{' '}
              <span>{explain.text}</span>
            </>
          )}
        </div>
      </Bot>
      {appliedFilters(ranking).length > 0 && (
        <div className="cb-muted" role="note">
          적용한 조건: {appliedFilters(ranking).join(' · ')}
        </div>
      )}
      {warnings.map((w) => (
        <div key={w} className="cb-warn" role="alert">
          {WARNING_TEXT[w](insurerCount, ranking.excludedItems.length)}
        </div>
      ))}
      <section className="cb-hero" aria-label="참고 순위 1위">
        <div className="cb-hero-main">
          <span className="cb-rank-pill">1위</span>
          <div className="cb-muted">{top.insurer}</div>
          <div className="cb-hero-name">{top.product}</div>
          <div className="cb-muted">보수적 순수령액</div>
          <div className="cb-hero-net">{won(top.conservativeNet)}</div>
        </div>
        <dl className="cb-stats">
          <Stat k="수령 예상" v={won(top.cappedReceipt)} />
          <Stat k="환수 노출" v={`−${won(top.clawbackExposure)}`} neg />
          <Stat k="한도 영향" v={top.capImpactPct == null ? '자료 없음' : `${top.capImpactPct.toFixed(1)}%`} />
          <Stat k="협회 등급" v={gradeLabel(top.associationGrade)} />
        </dl>
        <button type="button" className="cb-btn primary" onClick={() => onEvidence(top)}>
          근거 보기
        </button>
        {why && <div className="cb-hero-why">{why}</div>}
      </section>
      <div className="cb-table-wrap">
        <table className="cb-table">
          <caption className="visually-hidden">보험사별 비교 표</caption>
          <thead>
            <tr>
              <th scope="col">순위</th>
              <th scope="col">보험사 · 상품</th>
              <th scope="col" className="r">
                보수적 순수령액
              </th>
              <th scope="col" className="r">
                수령 예상
              </th>
              <th scope="col" className="r">
                환수 노출
              </th>
              <th scope="col">한도 영향</th>
              <th scope="col">협회 등급</th>
              <th scope="col">
                <span className="visually-hidden">근거</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {items.map((item) => {
              const badge = capBadge(item.capImpactPct)
              return (
                <tr key={item.offeringId}>
                  <td className={`rank${item.rank === 1 ? ' top' : ''}`}>{item.rank}</td>
                  <td>
                    <div className="cb-muted">{item.insurer}</div>
                    <div className="name">{item.product}</div>
                  </td>
                  <td className="r">
                    <b>{won(item.conservativeNet)}</b>
                  </td>
                  <td className="r">{won(item.cappedReceipt)}</td>
                  <td className="r neg">−{won(item.clawbackExposure)}</td>
                  <td>
                    <span className={`cb-badge ${badge.tone}`}>{badge.text}</span>
                  </td>
                  <td>
                    <span className={`cb-badge ${item.associationGrade ? 'grade' : 'none'}`}>
                      {gradeLabel(item.associationGrade)}
                    </span>
                  </td>
                  <td>
                    <button
                      type="button"
                      className="cb-btn small"
                      aria-label={`${item.insurer} ${item.product} 근거 보기`}
                      onClick={() => onEvidence(item)}
                    >
                      근거
                    </button>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      <p className="cb-note">
        보수적 순수령액 = 한도 반영 수령 예상액 − 환수 노출액(해지 시나리오 가정). 협회 등급은 표시만 하며 순위에
        반영하지 않아요. 기준 보험료는 내 계약의 평균 월 환산 보험료({won(ranking.basePremium ?? 0)})예요. 가상 데이터.
      </p>
    </>
  )
}

function Stat({ k, v, neg }: { k: string; v: string; neg?: boolean }) {
  return (
    <div className="cb-stat">
      <dt>{k}</dt>
      <dd className={neg ? 'neg' : undefined}>{v}</dd>
    </div>
  )
}

/** 근거 서랍: 서버가 준 근거 항목을 항목별로 묶어 그대로 보여 준다. 값은 카드·표와 같은 응답에서 온다. */
function Drawer({
  item,
  ranking,
  total,
  onClose,
}: {
  item: CompareItem
  ranking: CompareRanking
  total: number
  onClose: () => void
}) {
  const close = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    close.current?.focus({ preventScroll: true })
  }, [item])
  const groups = new Map<string, CompareEvidence[]>()
  for (const line of item.evidence) groups.set(line.label, [...(groups.get(line.label) ?? []), line])
  const badge = capBadge(item.capImpactPct)
  return (
    <aside className="cb-drawer" aria-label="산출 근거">
      <div className="cb-drawer-head">
        <div>
          <div className="cb-drawer-title">
            {item.insurer} · {item.product}
          </div>
          <div className="cb-muted">
            {item.rank}위 / {total}개 · 자료 기준 {ranking.asOf}
          </div>
        </div>
        <button ref={close} type="button" className="cb-close" aria-label="근거 닫기" onClick={onClose}>
          ×
        </button>
      </div>
      <div className="cb-drawer-body">
        <div className="cb-net">
          <div className="cb-net-label">실제로 남는 돈</div>
          <div className="cb-net-value">{won(item.conservativeNet)}</div>
          <div className="cb-ledger">
            <div>
              <span>받을 수 있는 수수료(한도 반영)</span>
              <b>{won(item.cappedReceipt)}</b>
            </div>
            {item.carryover > 0 && (
              <div>
                <span>한도 초과로 이월되는 예정액</span>
                <b>{won(item.carryover)}</b>
              </div>
            )}
            <div>
              <span>해지 시 돌려줄 금액</span>
              <b className="neg">−{won(item.clawbackExposure)}</b>
            </div>
          </div>
        </div>
        <div className="cb-rows">
          <div>
            <span className="k">1,200% 한도</span>
            <span className={`cb-badge ${badge.tone}`}>{badge.text}</span>
          </div>
          <div>
            <span className="k">협회 등급</span>
            <span className={`cb-badge ${item.associationGrade ? 'grade' : 'none'}`}>
              {gradeLabel(item.associationGrade)}
            </span>
            <small>{item.associationGrade ? '참고용 · 순위 미반영' : '순위에는 영향 없어요'}</small>
          </div>
        </div>
        {[...groups.entries()].map(([label, lines]) => (
          <section key={label} className="cb-calc">
            <h4>{label}</h4>
            {lines.map((line, index) => (
              <div key={index} className="cb-calc-line">
                <span>{line.rule}</span>
                {line.value != null && <b>{won(line.value)}</b>}
              </div>
            ))}
            <div className="cb-calc-src">
              {lines[0].source}
              {lines[0].policyVersion ? ` · ${lines[0].policyVersion}` : ''} · 기준일 {lines[0].asOf}
            </div>
          </section>
        ))}
        <p className="cb-muted">
          규칙 계산 결과예요. 환수 노출은 {ranking.clawbackMonth}개월차에 해지된다고 가정한 시나리오 금액이에요.
        </p>
      </div>
    </aside>
  )
}
