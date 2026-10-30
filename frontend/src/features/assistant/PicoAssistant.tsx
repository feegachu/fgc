import { ApiError } from '../../lib/api/client'
import { useEffect, useId, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import type { ShellUser } from '../../app/shell/Sidebar'
import { findGuide, GUIDE_CATALOG } from './catalog'
import type { AssistantContext, CalculationResult, MessageResult } from './contracts'
import { checkGuideProposal, isCurrentResponse, isWonAmount } from './guards'
import { PAYMENT_SCENARIOS, readPaymentFixture } from './fixtures'
import type { PaymentScenario } from './fixtures'
import basic from './assets/pico-basic.png'
import guide from './assets/pico-guide.png'
import detective from './assets/pico-detective.png'
import './pico-assistant.css'
import { PaymentCalculationPanel } from './PaymentCalculationPanel'
import { PaymentGuideOverlay } from './PaymentGuide'
import { useGuideRuntime } from './guide-runtime'
import { askPico, picoPaymentReference } from './pico-api'
import { PicoGuideStartButton } from './PicoGuideStartButton'
import type { PicoReply } from './pico-api'
import type { PicoTurn } from './pico-api'
import { screenFor } from '../../app/screens'
import { findWorkflowGuide, WORKFLOW_GUIDES, workflowSteps } from './workflow-catalog'
import { useWorkflowGuide } from './workflow-state'

const labels: Record<PaymentScenario, string> = {
  'PAY-01': '이번 달 수수료는 얼마야?',
  'PAY-02': '지급액 입력했는데 다음은?',
  'PAY-03': '귀속 금액이 맞지 않아요',
  'PAY-04': '이 금액으로 지급해도 돼?',
  'PAY-05': '금액을 15만원으로 바꾸면?',
  'PAY-06': '한도 20만원만큼 더 지급해도 돼?',
  'PAY-07': '지급액을 잘못 입력했어요',
  'PAY-08': '검증 후 금액을 수정했어요',
  'PAY-09': '수수료 수정하고 싶어요',
  'PAY-10': '조회 권한으로 지급 확정 요청',
  'PAY-11': '4년·7년 분급이면 월 얼마야?',
  'PAY-12': '다른 탭에서 지급이 변경됐어요',
}
const statuses = {
  CALCULATED: '계산 예시',
  REVIEW_REQUIRED: '검토 필요',
  INSUFFICIENT_DATA: '자료 부족',
  UNSUPPORTED: '지원 준비 중',
  STALE: '다시 확인 필요',
}
const amountLabels = {
  amount: '지급액',
  expectedAmount: '예상액',
  actualAmount: '실제 지급액',
  attributedTotal: '귀속 합계',
  differenceAmount: '차액',
  correctedAmount: '정정 전체액',
  limitAmount: '한도',
  includedAmount: '산입액',
  remainingAmount: '잔여 한도',
  currentExcessAmount: '현재 초과액',
  projectedExcessAmount: '예정 초과액',
}
const evidenceLabels = {
  ACTUAL: '실제 지급 합성 예시',
  EXPECTED: '예상 금액 합성 예시',
  HYPOTHETICAL: '가상 계산 예시',
}
type Entry =
  | { id: number; role: 'user'; text: string }
  | { id: number; role: 'notice'; text: string }
  | { id: number; role: 'assistant'; result: MessageResult }
  | { id: number; role: 'live'; reply: PicoReply; guideContext: string; hasPaymentContext: boolean }
interface Conversation {
  entries: Entry[]
  revision: number
  scenario: PaymentScenario
  plan: MessageResult | null
}
const initial = (): Conversation => ({ entries: [], revision: 0, scenario: 'PAY-02', plan: null })
function demoContext(revision: number, scenario: PaymentScenario): AssistantContext {
  return {
    screenId: 'TRAN-W02',
    paymentId: '1027',
    contractId: '101',
    settlementMonth: '2026-10',
    paymentStage: 'GA_TO_FC',
    installmentNo: 1,
    commissionItemId: '1',
    beneficiaryAgentId: '7',
    formDirty: scenario === 'PAY-08',
    clientRevision: revision,
  }
}
function Symbol({ name }: { name: string }) {
  return (
    <span className="material-symbols-rounded" aria-hidden="true">
      {name}
    </span>
  )
}
function Won({ amount }: { amount: string | null }) {
  return (
    <>
      {amount === null
        ? '확인 전'
        : isWonAmount(amount)
          ? `${BigInt(amount).toLocaleString('ko-KR')}원`
          : '금액 확인 필요'}
    </>
  )
}
function CalculationCard({ result, current }: { result: CalculationResult; current: boolean }) {
  return (
    <section className="pico-calculation" aria-label="합성 계산 결과">
      <div className="pico-calculation-heading">
        <span>{evidenceLabels[result.evidenceKind]}</span>
        <strong>{current ? statuses[result.status] : '이전 시연 결과'}</strong>
      </div>
      <dl>
        {Object.entries(result.amounts).map(([key, value]) => (
          <div key={key}>
            <dt>{amountLabels[key as keyof typeof amountLabels]}</dt>
            <dd>
              <Won amount={value ?? null} />
            </dd>
          </div>
        ))}
      </dl>
      {result.basis.sourceRefs.map((source) => (
        <p key={source.sourceId}>
          {source.title} · v{source.version}
        </p>
      ))}
      {result.missingInputs.length > 0 && <p>전체 지급 후보·귀속·원천 근거를 먼저 확인해야 합니다.</p>}
      <p>{result.recommendation.reason}</p>
      <small>합성 자료 · 입력값 반영/실제 지급 없음</small>
    </section>
  )
}

/** Explicit opt-in only: this shell widget is not the production AI or a server failure fallback. */
export function AssistantDemoMount({
  user,
  loggingOut = false,
  enabled = import.meta.env.VITE_ASSISTANT_DEMO === 'true' || import.meta.env.VITE_ASSISTANT_AI === 'true',
  screenId,
  screenContext,
}: {
  user: ShellUser
  loggingOut?: boolean
  enabled?: boolean
  screenId?: string
  screenContext?: string
}) {
  if (!enabled || loggingOut || !['SYSTEM_ADMIN', 'GA_ADMIN', 'SETTLEMENT', 'COMPLIANCE'].includes(user.roleCode))
    return null
  return <AssistantWithGuide key={`${user.loginId}:${user.roleCode}:${user.canProcess}`} canProcess={user.canProcess} roleCode={user.roleCode} screenId={screenId} screenContext={screenContext} />
}
function AssistantWithGuide({ canProcess, roleCode, screenId, screenContext }: { canProcess: boolean; roleCode?: string; screenId?: string; screenContext?: string }) {
  useEffect(() => () => useGuideRuntime.getState().reset(), [])
  return (
    <>
      <PicoAssistant canProcess={canProcess} roleCode={roleCode} screenId={screenId} screenContext={screenContext} />
      <PaymentGuideOverlay />
    </>
  )
}
export function PicoAssistant({ canProcess, roleCode, screenId, screenContext }: { canProcess: boolean; roleCode?: string; screenId?: string; screenContext?: string }) {
  const [open, setOpen] = useState(false)
  const aiEnabled = import.meta.env.VITE_ASSISTANT_AI === 'true'
  const currentScreen = screenId ?? screenFor(window.location.pathname)?.id ?? 'CMN-C04'
  const currentRole = roleCode ?? (canProcess ? 'SETTLEMENT' : 'GA_ADMIN')
  const availableWorkflows = WORKFLOW_GUIDES.filter((guide) => guide.roles.includes(currentRole))
  const suggestions = availableWorkflows.filter((guide) => guide.steps.some((step) => step.screenId === currentScreen)).slice(0, 3)
  const syntheticEnabled = !aiEnabled
  const [aiBusy, setAiBusy] = useState(false)
  const aiRequest = useRef<AbortController | null>(null)
  const aiSequence = useRef(0)
  const aiLock = useRef(false)
  const [calculationEpoch, setCalculationEpoch] = useState(0)
  const runtimeSession = useGuideRuntime((state) => state.session)
  const observed = useGuideRuntime((state) => state.observation)
  const workflowActive = useWorkflowGuide((state) => state.active)
  const paymentActive = useWorkflowGuide((state) => state.paymentActive)
  const activeGuideContext = useWorkflowGuide((state) => state.guideContext)
  const guideContext = JSON.stringify([
    observed?.owner,
    observed?.paymentId,
    observed?.scopeKey,
    observed?.revision,
    observed?.saved,
    observed?.precheck,
    observed?.modalOpen,
    canProcess,
    screenContext,
  ])
  const [latestLiveQuestion, setLatestLiveQuestion] = useState<{ context: string; revision: string | null }>({
    context: guideContext,
    revision: null,
  })
  // Actual target/input changes invalidate completed proposals; transient readiness only cancels transport.
  if (latestLiveQuestion.context !== guideContext) setLatestLiveQuestion({ context: guideContext, revision: null })
  const guideActive =
    runtimeSession != null && !['PAUSED', 'COMPLETED', 'CANCELLED', 'ERROR'].includes(runtimeSession.status)
  const visible = open && !guideActive && !workflowActive && !paymentActive
  const [tab, setTab] = useState<'chat' | 'plan'>('chat')
  const [input, setInput] = useState('')
  const [pickedScenario, setPickedScenario] = useState<PaymentScenario>('PAY-02')
  const [conversation, setConversation] = useState<Conversation>(initial)
  const [planError, setPlanError] = useState('')
  const textarea = useRef<HTMLTextAreaElement>(null),
    stream = useRef<HTMLDivElement>(null)
  const panelId = useId(),
    titleId = useId()
  useEffect(() => {
    if (!visible) return
    textarea.current?.focus()
    const close = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !document.querySelector('[aria-modal="true"]')) {
        event.preventDefault()
        setOpen(false)
      }
    }
    document.addEventListener('keydown', close)
    return () => {
      document.removeEventListener('keydown', close)
      document.getElementById(`${panelId}-launcher`)?.focus()
    }
  }, [visible, panelId])
  useEffect(() => {
    if (open && tab === 'chat')
      stream.current?.scrollTo?.({
        top: conversation.entries.length === 0 ? 0 : stream.current.scrollHeight,
        behavior: window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth',
      })
  }, [open, tab, conversation.entries])
  useEffect(
    () => () => {
      aiRequest.current?.abort()
      aiSequence.current++
      aiLock.current = false
      setAiBusy(false)
    },
    [
      visible,
      observed?.owner,
      observed?.scopeKey,
      observed?.paymentId,
      observed?.revision,
      observed?.saved,
      observed?.busy,
      observed?.ready,
      observed?.precheck,
      observed?.modalOpen,
      canProcess,
      screenContext,
    ],
  )
  function cancelAi() {
    aiRequest.current?.abort()
    aiSequence.current++
    aiLock.current = false
    setAiBusy(false)
  }
  async function sendLive(text: string) {
    if (aiLock.current || !text.trim()) return
    const controller = new AbortController(),
      sequence = ++aiSequence.current
    aiRequest.current = controller
    aiLock.current = true
    setAiBusy(true)
    setLatestLiveQuestion({ context: guideContext, revision: String(sequence) })
    setInput('')
    setTab('chat')
    setConversation((previous) => ({
      ...previous,
      entries: [...previous.entries, { id: previous.entries.length, role: 'user', text }],
    }))
    try {
      const paymentId = observed?.ready && observed.saved && !observed.busy ? observed.paymentId : null
      const generalGuide = activeGuideContext ? findWorkflowGuide(activeGuideContext.workflowId, '1.0') : null
      const paymentGuide = activeGuideContext ? findGuide(activeGuideContext.workflowId, '1.0') : null
      const contextGuide = generalGuide ?? paymentGuide
      const contextStep = (generalGuide ? workflowSteps(generalGuide) : contextGuide?.steps)?.find((step) => step.stepId === activeGuideContext?.stepId)
      const history = conversation.entries.flatMap((entry): PicoTurn[] =>
        entry.role === 'user'
          ? [{ role: 'USER', text: entry.text }]
          : entry.role === 'live' && entry.reply.source === 'AI'
            ? [{ role: 'ASSISTANT', text: entry.reply.message! }]
            : [],
      ).slice(contextStep ? -5 : -6)
      if (contextStep) history.push({ role: 'ASSISTANT', text: `화면 안내 맥락: ${contextGuide!.title} / ${contextStep.title}. ${contextStep.instruction}` })
      const reply = await askPico(
        text,
        String(sequence),
        observed ? 'TRAN-W02' : currentScreen,
        controller.signal,
        paymentId,
        history,
      )
      if (controller.signal.aborted || sequence !== aiSequence.current) return
      setConversation((previous) => ({
        ...previous,
        entries: [
          ...previous.entries,
          {
            id: previous.entries.length,
            role: 'live',
            reply,
            guideContext,
            hasPaymentContext: Boolean(picoPaymentReference(paymentId)),
          },
        ],
      }))
    } catch (error) {
      if (controller.signal.aborted || sequence !== aiSequence.current) return
      setConversation((previous) => ({
        ...previous,
        entries: [
          ...previous.entries,
          {
            id: previous.entries.length,
            role: 'notice',
            text:
              (error instanceof ApiError && error.code === 'FGC-IMPL-GUIDE_VERSION_MISMATCH') ||
              (error instanceof Error && ['INVALID_PICO_REPLY', 'INVALID_PICO_EVIDENCE', 'MISSING_PICO_EVIDENCE'].includes(error.message))
                ? '가이드 버전이 변경됐어요. 작성 중인 내용을 보존한 뒤 화면을 새로 열어주세요.'
                : '답변 서버에 연결하지 못했어요. 입력 내용은 유지되며 기존 업무 화면의 도움말을 사용할 수 있어요.',
          },
        ],
      }))
    } finally {
      if (sequence === aiSequence.current) {
        aiLock.current = false
        setAiBusy(false)
      }
    }
  }
  const currentContext = demoContext(conversation.revision, conversation.scenario)
  const plan = conversation.plan?.workflowProposal
    ? findGuide(conversation.plan.workflowProposal.workflowId, conversation.plan.workflowProposal.guideVersion)
    : null
  function runScenario(scenario: PaymentScenario, now: number) {
    if (!syntheticEnabled) {
      void sendLive(labels[scenario])
      return
    }
    cancelAi()
    setConversation((previous) => {
      const revision = previous.revision + 1,
        context = demoContext(revision, scenario)
      let result = readPaymentFixture(scenario, context, now)
      if (
        !canProcess &&
        result.workflowProposal &&
        findGuide(result.workflowProposal.workflowId, result.workflowProposal.guideVersion)?.capability === 'PROCESS'
      ) {
        result = readPaymentFixture('PAY-10', context, now)
      }
      return {
        scenario,
        revision,
        plan: null,
        entries: [
          ...previous.entries,
          { id: previous.entries.length, role: 'user', text: labels[scenario] },
          { id: previous.entries.length + 1, role: 'assistant', result },
        ],
      }
    })
    setPickedScenario(scenario)
    setInput('')
    setPlanError('')
    setTab('chat')
  }
  function send(event: FormEvent) {
    event.preventDefault()
    const text = input.trim()
    if (!text || aiLock.current) return
    if (aiEnabled) {
      void sendLive(text)
      return
    }
    setConversation((previous) => ({
      ...previous,
      entries: [
        ...previous.entries,
        { id: previous.entries.length, role: 'user', text },
        {
          id: previous.entries.length + 1,
          role: 'notice',
          text: '지금은 합성 질문 시연 모드예요. 자유 질문 해석은 아직 연결하지 않았어요. 위의 시연 질문을 선택해 답변과 안내 순서를 확인해주세요.',
        },
      ],
    }))
    setInput('')
    setTab('chat')
  }
  function showPlan(result: MessageResult, now: number) {
    const checked = checkGuideProposal(result, currentContext, { mode: 'synthetic', now })
    if (!checked.ok || (!canProcess && checked.definition.capability === 'PROCESS')) {
      setPlanError('이전 시연의 안내이거나 사용할 수 없는 제안입니다. 시연 질문을 다시 선택해주세요.')
      return
    }
    setConversation((previous) => ({ ...previous, plan: result }))
    setTab('plan')
    setPlanError('')
  }
  return (
    <div className="pico-assistant-root">
      {!visible && (
        <div className="pico-launcher">
          <span className="pico-launcher-hint">업무가 막히면 피코를 불러요</span>
          <button
            id={`${panelId}-launcher`}
            className="pico-launcher-button"
            aria-label="피코 업무 비서 열기"
            aria-expanded="false"
            aria-controls={panelId}
            onClick={() => {
              if (guideActive) useGuideRuntime.getState().pause()
              setOpen(true)
            }}
          >
            <img src={basic} alt="피코" />
          </button>
        </div>
      )}
      {visible && (
        <aside id={panelId} role="dialog" aria-modal="false" aria-labelledby={titleId} className="pico-panel">
          <header className="pico-panel-header">
            <div className="pico-avatar">
              <img src={plan && tab === 'plan' ? guide : basic} alt="피코" />
            </div>
            <div>
              <h2 id={titleId}>
                피코 <span>업무 비서</span>
              </h2>
              <p>필요한 업무부터 함께 찾아요</p>
            </div>
            <button
              className="pico-icon-button"
              aria-label="새 대화"
              onClick={() => {
                cancelAi()
                setConversation(initial())
                setCalculationEpoch((value) => value + 1)
                setPickedScenario('PAY-02')
                setInput('')
                setPlanError('')
                setTab('chat')
                textarea.current?.focus()
              }}
            >
              <Symbol name="refresh" />
            </button>
            <button className="pico-icon-button" aria-label="비서 닫기" onClick={() => setOpen(false)}>
              <Symbol name="close" />
            </button>
          </header>
          <div
            className="pico-tabs"
            role="tablist"
            aria-label="비서 화면"
            onKeyDown={(event) => {
              if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
              event.preventDefault()
              const next =
                event.key === 'Home' ? 'chat' : event.key === 'End' ? 'plan' : tab === 'chat' ? 'plan' : 'chat'
              setTab(next)
              document.getElementById(`${panelId}-${next}-tab`)?.focus()
            }}
          >
            <button
              id={`${panelId}-chat-tab`}
              role="tab"
              tabIndex={tab === 'chat' ? 0 : -1}
              aria-selected={tab === 'chat'}
              aria-controls={`${panelId}-chat`}
              onClick={() => setTab('chat')}
            >
              대화
            </button>
            <button
              id={`${panelId}-plan-tab`}
              role="tab"
              tabIndex={tab === 'plan' ? 0 : -1}
              aria-selected={tab === 'plan'}
              aria-controls={`${panelId}-plan`}
              onClick={() => setTab('plan')}
            >
              업무 안내
            </button>
          </div>
          <div
            ref={stream}
            className="pico-stream"
            role="tabpanel"
            id={`${panelId}-${tab}`}
            aria-labelledby={`${panelId}-${tab}-tab`}
          >
            {tab === 'chat' ? (
              <>
                {observed && (
                  <PaymentCalculationPanel
                    key={`${observed.owner}:${observed.paymentId}:${observed.scopeKey}:${observed.revision}:${observed.saved}:${observed.busy}:${observed.ready}:${calculationEpoch}`}
                    observation={observed}
                    canProcess={canProcess}
                  />
                )}
                {conversation.entries.length === 0 && (
                  <div className="pico-welcome">
                    <img src={basic} alt="피코" />
                    <h3>
                      어떤 업무를
                      <br />
                      도와드릴까요?
                    </h3>
                    <p>
                      하고 싶은 일을 편하게 물어보세요.
                      <br />
                      지금 필요한 화면과 입력 위치를 안내할게요.
                    </p>
                    <div className="pico-suggestions">
                      {aiEnabled ? (suggestions.length ? suggestions : availableWorkflows.slice(0, 3)).map((guide) => (
                        <button key={guide.workflowId} onClick={() => void sendLive(`${guide.title} 업무를 어떻게 진행하나요?`)}>
                          <span className="pico-suggestion-icon"><Symbol name="menu_book" /></span>
                          <span>{guide.title}<small>업무 절차와 입력 위치 안내</small></span>
                          <Symbol name="chevron_right" />
                        </button>
                      )) : (['PAY-02', 'PAY-01', 'PAY-07'] as const).map((scenario, index) => (
                        <button key={scenario} onClick={() => runScenario(scenario, Date.now())}>
                          <span className="pico-suggestion-icon">
                            <Symbol name={['payments', 'description', 'edit'][index]} />
                          </span>
                          <span>
                            {labels[scenario]}
                            <small>
                              {
                                [
                                  '현재 상태에서 이어서 진행',
                                  '예상 수수료와 계산 근거 확인',
                                  '초안 수정 또는 확정 건 정정',
                                ][index]
                              }
                            </small>
                          </span>
                          <Symbol name="chevron_right" />
                        </button>
                      ))}
                    </div>
                    <div className="pico-welcome-foot">
                      <Symbol name="menu_book" />
                      승인된 업무 가이드를 바탕으로 안내해요.
                    </div>
                  </div>
                )}
                <div className="pico-messages" aria-live="polite" aria-relevant="additions">
                  {conversation.entries.map((entry) =>
                    entry.role === 'user' ? (
                      <p className="pico-user-message" key={entry.id}>
                        {entry.text}
                      </p>
                    ) : entry.role === 'notice' ? (
                      <p className="pico-notice" key={entry.id}>
                        {entry.text}
                      </p>
                    ) : entry.role === 'live' ? (
                      <article className="pico-answer" key={entry.id}>
                        <div className="pico-answer-author">
                          <img src={guide} alt="피코" />
                          <b>피코</b>
                          <span>{entry.reply.source === 'AI' ? 'AI 가이드 답변' : '시스템 안내'}</span>
                        </div>
                        <p>{entry.reply.message}</p>
                        {entry.reply.evidenceRefs?.map((source) => (
                          <div className="pico-source" key={source.chunkId}>
                            <Symbol name="menu_book" />
                            {source.title} · v{entry.reply.guideVersion}
                          </div>
                        ))}
                        {entry.reply.decision === 'ANSWER_ONLY' &&
                          [...new Set(entry.reply.evidenceRefs?.map((source) => source.workflowId))]
                            .filter((id): id is string => Boolean(id))
                            .map((workflowId) => findWorkflowGuide(workflowId, '1.0') ? (
                              <button key={workflowId} type="button" className="button button-secondary"
                                onClick={() => { useGuideRuntime.getState().stop(); useWorkflowGuide.getState().start(workflowId, entry.reply.evidenceRefs?.find((ref) => ref.workflowId === workflowId && ref.stepId?.startsWith('help.'))?.stepId ?? undefined); setOpen(false) }}>
                                이 업무 안내 시작 · {findWorkflowGuide(workflowId, '1.0')?.title}
                              </button>
                            ) : !observed?.paymentId ? (
                              <button key={workflowId} type="button" className="button button-secondary" disabled={!canProcess}
                                onClick={() => { useGuideRuntime.getState().stop(); useWorkflowGuide.getState().start(workflowId, entry.reply.evidenceRefs?.find((ref) => ref.workflowId === workflowId && ref.stepId?.startsWith('help.'))?.stepId ?? undefined); setOpen(false) }}>
                                지급 작성 화면에서 안내 시작 · {findGuide(workflowId, '1.0')?.title}
                              </button>
                            ) : (
                              <PicoGuideStartButton
                                key={workflowId}
                                workflowId={workflowId}
                                contextRevision={entry.reply.contextRevision!}
                                canProcess={canProcess}
                                sourceContextAvailable={entry.hasPaymentContext}
                                contextValid={
                                  !aiBusy &&
                                  entry.guideContext === guideContext &&
                                  entry.reply.contextRevision === latestLiveQuestion.revision
                                }
                              />
                            ))}
                        <small>일반 업무 안내 · 현재 지급 처리 완료를 뜻하지 않습니다.</small>
                      </article>
                    ) : (
                      <article className="pico-answer" key={entry.id}>
                        <div className="pico-answer-author">
                          <img
                            src={
                              entry.result.calculations.some((calc) => calc.kind === 'RECONCILIATION_DIFFERENCE')
                                ? detective
                                : guide
                            }
                            alt="피코"
                          />
                          <b>피코</b>
                          <span>합성 질문 시연</span>
                        </div>
                        <p>{entry.result.text.replace('[합성 시연 · API/AI 미연결] ', '')}</p>
                        {!isCurrentResponse(entry.result, currentContext) && (
                          <p className="pico-stale">이전 시연 상황의 응답입니다.</p>
                        )}
                        {entry.result.calculations.map((result) => (
                          <CalculationCard
                            key={result.calculationId}
                            result={result}
                            current={isCurrentResponse(result, currentContext)}
                          />
                        ))}
                        {entry.result.sources.map((source) => (
                          <div className="pico-source" key={source.sourceId}>
                            <Symbol name="menu_book" />
                            {source.title} · v{source.version}
                          </div>
                        ))}
                        {entry.result.workflowProposal && (
                          <button
                            className="pico-primary"
                            disabled={!isCurrentResponse(entry.result, currentContext)}
                            onClick={() => showPlan(entry.result, Date.now())}
                          >
                            안내 순서 보기
                            <Symbol name="chevron_right" />
                          </button>
                        )}
                        {entry.result.clarification && (
                          <p className="pico-clarification">
                            {entry.result.clarification.options.length
                              ? entry.result.clarification.options.map((option) => option.label).join(' / ')
                              : '현재 시연은 예시 데이터입니다. 실제 대상 선택은 화면 연결 단계에서 제공됩니다.'}
                          </p>
                        )}
                      </article>
                    ),
                  )}
                </div>
              </>
            ) : aiEnabled ? (
              <div className="pico-plan">
                <h3>업무 안내</h3>
                <p>원하는 업무를 선택하면 해당 화면과 입력 위치를 안내해요. 작성 중인 입력이 있으면 이동 전에 확인합니다.</p>
                <div className="pico-suggestions">
                  {[...availableWorkflows].sort((left, right) => Number(right.steps.some((step) => step.screenId === currentScreen)) - Number(left.steps.some((step) => step.screenId === currentScreen))).map((workflow) => (
                    <button key={workflow.workflowId} type="button" className="pico-suggestion"
                      onClick={() => { useGuideRuntime.getState().stop(); useWorkflowGuide.getState().start(workflow.workflowId); setOpen(false) }}>
                      <span><b>{workflow.title}</b><small>{workflow.description}</small></span><Symbol name="chevron_right" />
                    </button>
                  ))}
                  {GUIDE_CATALOG.filter((workflow) => workflow.roles.some((role) => role === currentRole)).map((workflow) => (
                    <button key={workflow.workflowId} type="button" className="pico-suggestion"
                      onClick={() => { useGuideRuntime.getState().stop(); useWorkflowGuide.getState().start(workflow.workflowId); setOpen(false) }}>
                      <span><b>{workflow.title}</b><small>지급 화면의 실제 입력·저장·검증 상태를 확인하며 안내합니다.</small></span><Symbol name="chevron_right" />
                    </button>
                  ))}
                </div>
                <p className="pico-plan-boundary">화면 안내는 업무 처리 완료를 뜻하지 않아요. 저장·계산·확정은 기존 업무 화면에서 직접 진행하세요.</p>
              </div>
            ) : (
              <div className="pico-plan">
                <img src={guide} alt="업무를 안내하는 피코" />
                <h3>{plan ? plan.title : '진행 중인 안내가 없어요.'}</h3>
                <p>
                  {plan
                    ? '안내할 업무의 순서를 미리 확인하세요.'
                    : '대화에서 시연 질문을 선택하면 안내 순서를 볼 수 있어요.'}
                </p>
                {plan && (
                  <>
                    <ol>
                      {plan.steps.map((step) => (
                        <li key={step.stepId}>
                          <b>{step.title}</b>
                          <p>{step.instruction}</p>
                        </li>
                      ))}
                    </ol>
                    {observed?.ready && plan && (
                      <button
                        type="button"
                        className="pico-plan-button"
                        onClick={() => {
                          if (useGuideRuntime.getState().start(plan.workflowId)) setOpen(false)
                        }}
                      >
                        현재 지급 화면의 고정 도움말 시작
                      </button>
                    )}
                    {!observed && (
                      <p>
                        실제 안내는 지급 건 작성 화면에서 시작할 수 있어요. 입력 보호를 위해 자동 이동하지 않습니다.
                      </p>
                    )}
                  </>
                )}
                <p className="pico-plan-boundary">
                  시연 순서와 별개로 현재 지급 화면의 고정 도움말을 시작할 수 있어요. 저장·확정은 직접 실행합니다.
                </p>
              </div>
            )}
            {planError && (
              <p className="pico-stale" role="status">
                {planError}
              </p>
            )}
          </div>
          <div className="pico-compose-region">
            {syntheticEnabled && (
              <details className="pico-demo-picker">
                <summary>시연 질문 바꾸기</summary>
                <label>
                  시연 상황
                  <select
                    value={pickedScenario}
                    onChange={(event) => setPickedScenario(event.target.value as PaymentScenario)}
                  >
                    {PAYMENT_SCENARIOS.map((scenario) => (
                      <option key={scenario} value={scenario}>
                        {labels[scenario]}
                      </option>
                    ))}
                  </select>
                </label>
                <button className="pico-demo-run" onClick={() => runScenario(pickedScenario, Date.now())}>
                  선택한 질문 시연
                </button>
              </details>
            )}
            {aiBusy && (
              <p className="pico-notice" role="status">
                승인된 가이드로 답변을 준비하고 있어요.
              </p>
            )}
            <form onSubmit={send} className="pico-composer">
              <textarea
                ref={textarea}
                aria-label="피코에게 질문"
                placeholder="예: 지급액을 잘못 입력했어요"
                maxLength={2000}
                value={input}
                onChange={(event) => setInput(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
                    event.preventDefault()
                    event.currentTarget.form?.requestSubmit()
                  }
                }}
              />
              <button aria-label="질문 보내기" type="submit" disabled={!input.trim() || aiBusy}>
                <Symbol name="arrow_upward" />
              </button>
            </form>
            <small>
              {aiEnabled
                ? '승인된 업무 가이드 · 실제 계산과 처리는 업무 화면에서 확인'
                : observed
                  ? '시연 질문은 합성 자료 · 저장된 지급 계산은 별도 조회 · 실제 AI 미연결'
                  : '합성 데이터 시연 · 실제 AI/계약/지급과 연결되지 않습니다.'}
            </small>
          </div>
        </aside>
      )}
    </div>
  )
}
