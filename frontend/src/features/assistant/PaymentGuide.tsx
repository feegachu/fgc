import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { findGuide } from './catalog'
import { completion, resolvePaymentTarget, useGuideRuntime } from './guide-runtime'
import mascot from './assets/pico-guide.png'
import './payment-guide.css'

export function PaymentGuideHelp({ canProcess }: { canProcess: boolean }) {
  if (import.meta.env.VITE_ASSISTANT_DEMO !== 'true') return null
  return (
    <div className="payment-guide-help" aria-label="지급 화면 고정 도움말">
      <button
        type="button"
        className="button button-secondary"
        data-guide-launch
        onClick={() => useGuideRuntime.getState().start('payment.amount.review')}
      >
        금액 확인 도움말
      </button>
      {canProcess && (
        <>
          <button
            type="button"
            className="button button-secondary"
            data-guide-launch
            onClick={() => useGuideRuntime.getState().start('payment.draft')}
          >
            초안 작성 도움말
          </button>
          <button
            type="button"
            className="button button-secondary"
            data-guide-launch
            onClick={() => useGuideRuntime.getState().start('payment.confirm')}
          >
            검증·확정 도움말
          </button>
        </>
      )}
    </div>
  )
}
export function PaymentGuideOverlay() {
  const session = useGuideRuntime((state) => state.session)
  const observation = useGuideRuntime((state) => state.observation)
  const [box, setBox] = useState<{
    key: string
    rect: { left: number; top: number; width: number; height: number }
  } | null>(null)
  const returnFocus = useRef<HTMLElement | null>(null)
  const guide = session ? findGuide(session.workflowId, '1.0') : null
  const step = guide?.steps[session?.index ?? 0]
  const running = session != null && !['PAUSED', 'CANCELLED', 'COMPLETED', 'ERROR'].includes(session.status)
  const target =
    step?.targetId === 'payment.confirm' && observation?.modalOpen ? 'payment.confirm.dialog' : step?.targetId
  const measurementKey = `${target}:${session?.generation}:${session?.revision}`
  const key = session ? `${session.owner}:${session.workflowId}:${session.generation}` : ''
  useEffect(() => {
    if (!key) return
    returnFocus.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
    return () => {
      if (returnFocus.current?.isConnected) returnFocus.current.focus()
    }
  }, [key])
  useEffect(() => {
    if (!running || !target) return
    let mounted = true,
      element: HTMLElement | null = null,
      found = false
    let frame = 0
    const runtime = useGuideRuntime.getState
    const timeout = window.setTimeout(() => {
      if (!found) runtime().pause('5초 안에 보이는 안내 대상을 찾지 못했어요. 화면을 확인하고 다시 시도하세요.')
    }, 5000)
    const measure = () => {
      try {
        if (!mounted) return
        const resolved = resolvePaymentTarget(document, target)
        if (resolved.error) {
          runtime().pause(resolved.error)
          return
        }
        if (!resolved.element) {
          if (found) runtime().pause('강조하던 대상이 사라지거나 숨겨졌어요. 화면을 확인한 뒤 다시 시도하세요.')
          return
        }
        if (element !== resolved.element) {
          element = resolved.element
          resize.observe(element)
          element.scrollIntoView?.({
            block: 'center',
            inline: 'nearest',
            behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth',
          })
        }
        found = true
        clearTimeout(timeout)
        const r = element.getBoundingClientRect()
        const left = Math.max(4, r.left - 5),
          top = Math.max(4, r.top - 5)
        const next = {
          left,
          top,
          width: Math.max(0, Math.min(innerWidth - 4, r.right + 5) - left),
          height: Math.max(0, Math.min(innerHeight - 4, r.bottom + 5) - top),
        }
        setBox((old) =>
          old?.key === measurementKey &&
          Object.keys(next).every((k) => old.rect[k as keyof typeof next] === next[k as keyof typeof next])
            ? old
            : { key: measurementKey, rect: next },
        )
        runtime().targetReady()
      } catch {
        runtime().fail()
      }
    }
    const schedule = () => {
      cancelAnimationFrame(frame)
      frame = requestAnimationFrame(measure)
    }
    const mutation = new MutationObserver(schedule)
    mutation.observe(document.body, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: [
        'class',
        'hidden',
        'inert',
        'aria-hidden',
        'data-guide-target',
        'data-guide-version',
        'data-guide-screen',
        'style',
      ],
    })
    const resize = new ResizeObserver(schedule)
    resize.observe(document.body)
    window.addEventListener('resize', schedule)
    window.addEventListener('scroll', schedule, true)
    schedule()
    return () => {
      mounted = false
      clearTimeout(timeout)
      cancelAnimationFrame(frame)
      mutation.disconnect()
      resize.disconnect()
      window.removeEventListener('resize', schedule)
      window.removeEventListener('scroll', schedule, true)
    }
  }, [running, target, session?.index, session?.owner, session?.revision, session?.generation, measurementKey])
  useEffect(() => {
    if (!session || ['CANCELLED', 'COMPLETED'].includes(session.status)) return
    const handler = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !document.querySelector('[aria-modal="true"]')) {
        event.preventDefault()
        useGuideRuntime.getState().pause()
      }
    }
    document.addEventListener('keydown', handler)
    return () => document.removeEventListener('keydown', handler)
  }, [session])
  if (!session || !guide || !step) return null
  const terminal = ['COMPLETED', 'CANCELLED'].includes(session.status)
  const checked = observation
    ? completion(session, observation)
    : { ok: false, reason: '지급 화면으로 돌아와 도움말을 새로 시작하세요.' }
  const modal = observation?.modalOpen
    ? document.querySelector<HTMLElement>('.transaction-confirm-modal[role="dialog"]')
    : null
  const card = (
    <aside className={`payment-guide-card${modal ? ' is-in-modal' : ''}`} aria-label="피코 지급 단계 안내">
      <header>
        <img src={mascot} alt="" />
        <div>
          <strong>{guide.title}</strong>
          <small>
            {session.index + 1} / {guide.steps.length} ·{' '}
            {['PAUSED', 'ERROR'].includes(session.status)
              ? '중지됨'
              : session.status === 'BLOCKED'
                ? '진행 조건 확인'
                : terminal
                  ? '안내 종료'
                  : '고정 도움말'}
          </small>
        </div>
        <button
          type="button"
          aria-label="가이드 종료"
          onClick={() => {
            useGuideRuntime.getState().reset()
            if (returnFocus.current?.isConnected) returnFocus.current.focus()
          }}
        >
          <span className="material-symbols-rounded" aria-hidden="true">
            close
          </span>
        </button>
      </header>
      <h3>{step.title}</h3>
      <p>{step.instruction}</p>
      <p role="status">{session.reason || checked.reason}</p>
      {!terminal && (
        <nav aria-label="가이드 단계 조작">
          <button
            type="button"
            disabled={session.index === 0 || ['PAUSED', 'ERROR'].includes(session.status)}
            onClick={() => useGuideRuntime.getState().previous()}
          >
            이전
          </button>
          {['PAUSED', 'ERROR'].includes(session.status) ? (
            <button
              type="button"
              disabled={session.restartRequired || !observation}
              onClick={() => useGuideRuntime.getState().resume()}
            >
              복귀·다시 시도
            </button>
          ) : (
            <>
              <button type="button" onClick={() => useGuideRuntime.getState().pause()}>
                중지
              </button>
              <button
                type="button"
                disabled={session.status === 'PREPARING'}
                onClick={() => useGuideRuntime.getState().next()}
              >
                {session.index === guide.steps.length - 1 ? '확인 완료' : '확인 후 다음'}
              </button>
            </>
          )}
          {session.restartRequired && observation?.ready && (
            <button type="button" onClick={() => useGuideRuntime.getState().start(session.workflowId)}>
              현재 대상으로 새로 시작
            </button>
          )}
        </nav>
      )}
      <small>직접 입력·저장하는 안내입니다. 자동 처리하지 않습니다.</small>
    </aside>
  )
  return (
    <>
      {running && box?.key === measurementKey && (
        <div aria-hidden="true" className="payment-guide-spotlight" style={box.rect} />
      )}
      {createPortal(card, modal ?? document.body)}
    </>
  )
}
