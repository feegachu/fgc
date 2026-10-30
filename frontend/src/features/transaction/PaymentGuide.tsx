import { useSpotlight } from '../assistant/guide/useSpotlight'
import { useWorkflowGuide } from '../assistant/workflow-state'
import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react'
import type { RefObject } from 'react'
import { createPortal } from 'react-dom'
import { Button } from '../../components/Button'
import { PAYMENT_GUIDES } from '../assistant/guide/catalog'
import { GUIDE_CATALOG } from '../assistant/catalog'
import { GuideRuntime } from '../assistant/guide/runtime'
import { GuideTargets } from '../assistant/guide/targets'
import type { GuideDefinition } from '../assistant/guide/types'
import { getPaymentGuideObserver, subscribePaymentGuide, paymentGuideConditions } from './payment-guide'
import type { PaymentGuideObserver, PaymentGuideState } from './payment-guide'
import './payment-guide.css'

interface Props {
  initialWorkflowId?: string | null
  root: RefObject<HTMLDivElement | null>
  modalOpen: boolean
  confirmed: boolean
  onClose(): void
  onList(): void
}
export function PaymentGuide(props: Props) {
  const observer = useSyncExternalStore(subscribePaymentGuide, getPaymentGuideObserver)
  return observer ? <GuideSession {...props} observer={observer} /> : null
}
const acknowledgements = new Set([
  'SCOPE_ACKNOWLEDGED',
  'AMOUNT_REVIEW_ACKNOWLEDGED',
  'ATTRIBUTIONS_REVIEWED',
  'PRECHECK_RESULT_ACKNOWLEDGED',
])
const reasons: Record<string, string> = {
  PERMISSION_DENIED: '현재 권한으로 이 안내를 계속할 수 없습니다.',
  CONTEXT_CHANGED: '입력 또는 저장 상태가 바뀌었습니다. 현재 내용을 확인한 뒤 이어서 안내하세요.',
  ENTITY_CHANGED_RESTART_REQUIRED: '선택 대상이 바뀌었습니다. 현재 대상으로 안내를 다시 시작하세요.',
  REQUIRED_ENTITY_MISSING: '이 안내에 필요한 계약 또는 저장된 지급 건을 먼저 선택하세요.',
  DUPLICATE_TARGET: '안내 대상이 중복되어 멈췄습니다.',
  PREPARATION_TIMEOUT: '안내 대상을 준비하지 못했습니다. 화면을 확인하고 다시 찾으세요.',
  TARGET_CHANGED: '안내 대상이 사라지거나 바뀌었습니다.',
  SERVER_STATE_CHANGED: '서버 저장 상태가 바뀌었습니다. 현재 내용을 다시 확인하세요.',
}
function GuideSession({
  initialWorkflowId,
  observer,
  root,
  modalOpen,
  confirmed,
  onClose,
  onList,
}: Props & { observer: PaymentGuideObserver }) {
  const runtime = useMemo(() => {
    const targets = new GuideTargets()
    const registered = new Set<string>()
    for (const guide of PAYMENT_GUIDES)
      for (const step of guide.steps) {
        const key = `${guide.guideVersion}:${step.targetId}`
        if (!registered.has(key)) {
          targets.register({
            screenId: step.screenId,
            guideVersion: guide.guideVersion,
            targetId: step.targetId,
            root: () => root.current,
          })
          registered.add(key)
        }
      }
    return new GuideRuntime(PAYMENT_GUIDES, targets, observer.environment())
  }, [observer, root])
  const subscribe = useMemo(() => (listener: () => void) => runtime.observe(listener), [runtime])
  const snapshot = useSyncExternalStore(subscribe, runtime.getSnapshot)
  useEffect(() => {
    if (snapshot.workflowId && snapshot.step) useWorkflowGuide.setState({ guideContext: { workflowId: snapshot.workflowId, stepId: snapshot.step.stepId } })
  }, [snapshot.workflowId, snapshot.step])
  const revision = useSyncExternalStore(observer.subscribe, observer.getViewRevision)
  const [collapsed, setCollapsed] = useState(false)
  const [message, setMessage] = useState('')
  const [working, setWorking] = useState(false)
  const [refresh, setRefresh] = useState(0)
  const [readError, setReadError] = useState('')
  const [assessment, setAssessment] = useState<{ key: string; state: PaymentGuideState } | null>(null)
  const card = useRef<HTMLElement>(null)
  const heading = useRef<HTMLHeadingElement>(null)
  const current = PAYMENT_GUIDES.find((guide) => guide.workflowId === snapshot.workflowId)
  const key = `${revision}:${snapshot.step?.stepId ?? ''}:${refresh}`
  const state = assessment?.key === key ? assessment.state : null
  const finalizing = useRef(false)
  useEffect(() => {
    if (!initialWorkflowId) return
    const guide = PAYMENT_GUIDES.find((guide) => guide.workflowId === initialWorkflowId)
    if (!guide) return
    try {
      const context = observer.readContext()
      void runtime.start({ workflowId: guide.workflowId, stepId: guide.steps[0].stepId, guideVersion: guide.guideVersion,
        entityRefs: context.entityRefs, clientContextRevision: context.clientContextRevision, serverStateVersion: context.serverStateVersion })
    } catch {
      setMessage('현재 선택 대상과 권한을 확인한 뒤 안내를 다시 시작해주세요.')
    }
  }, [initialWorkflowId, observer, runtime])

  useEffect(() => {
    const changed = () => runtime.contextChanged()
    const unsubscribe = observer.subscribe(changed)
    const mutations = new MutationObserver(changed)
    if (root.current)
      mutations.observe(root.current, {
        subtree: true,
        childList: true,
        attributes: true,
        attributeFilter: ['hidden', 'inert', 'aria-hidden', 'style', 'class', 'data-guide-target'],
      })
    return () => {
      unsubscribe()
      mutations.disconnect()
      runtime.stop()
    }
  }, [observer, root, runtime])
  useEffect(() => {
    const escape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || modalOpen) return
      event.preventDefault()
      event.stopPropagation()
      runtime.stop()
      onClose()
    }
    document.addEventListener('keydown', escape, true)
    return () => document.removeEventListener('keydown', escape, true)
  }, [modalOpen, onClose, runtime])
  useEffect(() => {
    if (!snapshot.step) return
    const controller = new AbortController()
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(5000)])
    void (async () => {
      try {
        const result = await observer.readBusinessState(observer.readContext(), signal)
        if (!signal.aborted) {
          setAssessment({ key, state: result })
          setReadError('')
        }
      } catch {
        if (!controller.signal.aborted) {
          setAssessment(null)
          setReadError('최신 업무 상태를 조회하지 못했습니다. 다시 조회하세요.')
        }
      }
    })()
    return () => controller.abort()
  }, [key, observer, snapshot.step])
  // Only the final confirmation step may auto-finish, after the real handler accepted CONFIRMED + GET.
  useEffect(() => {
    if (
      !confirmed ||
      modalOpen ||
      finalizing.current ||
      snapshot.step?.stepId !== 'payment.confirm' ||
      snapshot.status === 'COMPLETED' ||
      !state?.confirmed
    )
      return
    finalizing.current = true
    void (async () => {
      if (runtime.getSnapshot().status === 'PAUSED') await runtime.resume()
      await runtime.next()
      finalizing.current = false
    })()
  }, [confirmed, modalOpen, runtime, snapshot.status, snapshot.step, state])
  const completed = snapshot.status === 'COMPLETED'
  useEffect(() => {
    if (!modalOpen) heading.current?.focus()
  }, [modalOpen, snapshot.workflowId, completed, confirmed])

  const close = () => {
    runtime.stop()
    onClose()
  }
  const run = async (action: () => Promise<void>) => {
    if (working) return
    setWorking(true)
    setMessage('')
    try {
      await action()
    } catch (error) {
      setMessage(reasons[error instanceof Error ? error.message : ''] ?? '현재 화면에서 안내를 준비할 수 없습니다.')
    } finally {
      setWorking(false)
    }
  }
  const start = async (guide: GuideDefinition, stepId = guide.steps[0].stepId) => {
    const context = observer.readContext()
    runtime.stop()
    await runtime.start({
      workflowId: guide.workflowId,
      guideVersion: guide.guideVersion,
      stepId,
      entityRefs: context.entityRefs,
      clientContextRevision: context.clientContextRevision,
      serverStateVersion: context.serverStateVersion,
    })
  }
  const resume = async () => {
    await runtime.resume()
    if (runtime.getSnapshot().reasonCode === 'ENTITY_CHANGED_RESTART_REQUIRED' && current) {
      // A newly issued payment ID can be revalidated at the saved-result step; other selections restart at scope.
      await start(
        current,
        snapshot.step?.stepId === 'payment.save' && state?.saved ? 'payment.save' : current.steps[0].stepId,
      )
    }
  }
  const next = async () => {
    const rule = snapshot.step?.completionConditionId
    if (rule && acknowledgements.has(rule))
      observer.acknowledge(rule as Parameters<PaymentGuideObserver['acknowledge']>[0])
    await runtime.next()
  }
  let available = PAYMENT_GUIDES
  try {
    const context = observer.readContext()
    available = available.filter((guide) => guide.roles.includes(context.role))
  } catch {
    available = []
  }
  const step = snapshot.step
  const candidate =
    state && step ? { ...state, acknowledged: [...state.acknowledged, step.completionConditionId] } : null
  let canNext = false
  try {
    canNext = !!(
      candidate &&
      step &&
      paymentGuideConditions[step.completionConditionId](observer.readContext(), candidate) === 'SATISFIED'
    )
  } catch {
    /* Changed selection is handled by the runtime pause state. */
  }
  const waitingInstruction =
    step?.stepId === 'payment.scope'
      ? '① 지급 건에서 정산월·지급단계와 수령 설계사를 선택하세요. 원수사→GA는 설계사 선택이 필요 없습니다. 계약은 3단계 귀속행에서 선택합니다.'
      : step?.stepId === 'payment.amount'
        ? '① 지급 건에서 수수료 항목과 0원 이상의 지급액을 입력하세요.'
        : step?.stepId === 'payment.attributions'
          ? '② 귀속행 추가 후 계약번호·귀속일·금액을 입력하세요. 귀속금액 합계를 지급액과 맞추세요.'
          : (state?.validationMessage ?? '화면의 실제 업무 버튼을 눌러 처리 결과를 확인하세요.')
  const paused = snapshot.status === 'PAUSED' || snapshot.status === 'ERROR'
  const complete = snapshot.status === 'COMPLETED'
  const folded = collapsed && !confirmed && !complete
  const title = confirmed
    ? '지급 확정 완료'
    : complete
      ? '안내 완료'
      : (GUIDE_CATALOG.find((guide) => guide.workflowId === snapshot.workflowId)?.steps.find(
          (entry) => entry.stepId === step?.stepId,
        )?.title ?? '지급 업무 도움말')
  const position = useSpotlight(modalOpen || folded || paused || confirmed ? null : snapshot.target, card)
  if (modalOpen) return null
  return createPortal(
    <>
      {!modalOpen && !folded && !paused && !confirmed && snapshot.target && position.box && (
        <div
          className="payment-guide-spotlight"
          data-testid="payment-guide-spotlight"
          aria-hidden="true"
          style={position.box}
        />
      )}
      <section data-guide-feature="features.transaction.payment-guide.section"
        ref={card}
        className={`payment-guide-card${folded ? ' is-collapsed' : ''}`}
        style={position.card}
        aria-label="지급 업무 가이드"
        data-guide-status={snapshot.status}
      >
        <header className="payment-guide-header">
          <svg className="payment-guide-mascot" width="28" height="28" viewBox="0 0 28 28" aria-hidden="true">
            <rect x="3" y="6" width="22" height="18" rx="7" fill="currentColor" opacity=".12" />
            <path
              d="M8 17c2 3 10 3 12 0M14 3v3"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
            />
            <circle cx="9" cy="12" r="1.5" fill="currentColor" />
            <circle cx="19" cy="12" r="1.5" fill="currentColor" />
          </svg>
          <span>
            업무 가이드
            {current && step
              ? ` · ${current.steps.findIndex((entry) => entry.stepId === step.stepId) + 1}/${current.steps.length}`
              : ''}
          </span>
          {!confirmed && (
            <Button data-guide-feature="features.transaction.payment-guide.button" variant="ghost" onClick={() => setCollapsed(!collapsed)}>
              {folded ? '펼치기' : '접기'}
            </Button>
          )}
          <Button data-guide-feature="features.transaction.payment-guide.button-2" variant="ghost" onClick={close} aria-label="가이드 종료">
            종료
          </Button>
        </header>
        {!folded && (
          <>
            <h2 ref={heading} tabIndex={-1}>
              {title}
            </h2>
            <div aria-live="polite" aria-atomic="true">
              {confirmed ? (
                <p>
                  {state?.confirmed
                    ? '서버에서 지급 확정과 저장 결과를 확인했습니다. 목록으로 이동할 수 있습니다.'
                    : '지급 확정은 성공했습니다. 가이드 완료 확인은 보류되었습니다. 목록에서 처리 결과를 확인할 수 있습니다.'}
                </p>
              ) : complete ? (
                <p>
                  {current?.workflowId === 'payment.draft'
                    ? '현재 초안 저장을 확인했습니다. 지급 확정은 별도 절차입니다.'
                    : '안내를 마쳤습니다. 이 안내가 지급을 자동으로 확정하지는 않습니다.'}
                </p>
              ) : (
                <p>
                  {(step?.stepId === 'payment.precheck.result'
                    ? '사전검증 통과 여부와 차단 사유를 확인하세요.'
                    : step?.instruction) ??
                    '필요한 안내를 선택하세요. 저장·사전검증·확정은 화면의 실제 버튼을 직접 눌러 진행합니다.'}
                </p>
              )}
              {!confirmed && !complete && step && (
                <p className="payment-guide-condition">
                  {paused
                    ? (reasons[snapshot.reasonCode ?? ''] ??
                      '안내가 중단되었습니다. 현재 화면을 확인하고 다시 찾으세요.')
                    : canNext
                      ? '조건을 확인했습니다. 다음 단계로 진행할 수 있습니다.'
                      : waitingInstruction}
                  {paused && !canNext && ` ${waitingInstruction}`}
                </p>
              )}
              {message && <p role="alert">{message}</p>}
              {!step && paused && (
                <p role="alert">{reasons[snapshot.reasonCode ?? ''] ?? '현재 상태에서는 안내를 시작할 수 없습니다.'}</p>
              )}
              {readError && !confirmed && (
                <p role="alert">
                  {readError}{' '}
                  <Button data-guide-feature="features.transaction.payment-guide.button-3" variant="secondary" onClick={() => setRefresh(refresh + 1)}>
                    다시 조회
                  </Button>
                </p>
              )}
            </div>
            <div className="payment-guide-actions">
              {confirmed ? (
                <Button data-guide-feature="features.transaction.payment-guide.button-4"
                  onClick={() => {
                    runtime.stop()
                    onList()
                  }}
                >
                  목록으로 이동
                </Button>
              ) : complete ? (
                <>
                  <Button data-guide-feature="features.transaction.payment-guide.button-5" onClick={close}>안내 마치기</Button>
                  {current?.workflowId === 'payment.draft' && (
                    <Button data-guide-feature="features.transaction.payment-guide.button-6"
                      variant="secondary"
                      onClick={() =>
                        void run(() => start(PAYMENT_GUIDES.find((guide) => guide.workflowId === 'payment.confirm')!))
                      }
                    >
                      사전검증·확정 안내
                    </Button>
                  )}
                </>
              ) : !step ? (
                available.map((guide) => (
                  <Button data-guide-feature="features.transaction.payment-guide.button-7"
                    key={guide.workflowId}
                    variant="secondary"
                    disabled={working}
                    onClick={() => void run(() => start(guide))}
                  >
                    {guide.title}
                  </Button>
                ))
              ) : paused ? (
                <Button data-guide-feature="features.transaction.payment-guide.button-8"
                  disabled={working}
                  onClick={() => void run(snapshot.status === 'ERROR' ? () => start(current!) : resume)}
                >
                  현재 내용으로 다시 찾기
                </Button>
              ) : (
                <>
                  <Button data-guide-feature="features.transaction.payment-guide.button-9"
                    variant="secondary"
                    disabled={working || snapshot.status === 'PREPARING' || current?.steps[0].stepId === step.stepId}
                    onClick={() => void run(() => runtime.previous())}
                  >
                    이전
                  </Button>
                  <Button data-guide-feature="features.transaction.payment-guide.button-10"
                    disabled={!canNext || working || snapshot.status === 'PREPARING'}
                    onClick={() => void run(next)}
                  >
                    {acknowledgements.has(step.completionConditionId) ? '확인하고 다음' : '다음'}
                  </Button>
                </>
              )}
            </div>
            {!available.length && !confirmed && <p>현재 권한 또는 선택 대상으로 제공할 수 있는 안내가 없습니다.</p>}
          </>
        )}
      </section>
    </>,
    document.body,
  )
}
