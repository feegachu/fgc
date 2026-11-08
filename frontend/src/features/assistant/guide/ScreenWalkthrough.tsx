import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { Ref } from 'react'
import { createPortal } from 'react-dom'
import { Button } from '../../../components/Button'
import { closeSettlementGuide, settlementEntry, subscribeSettlementEntry } from '../settlement/entry'
import guideMascot from '../assets/pico-guide.png'
import type { ScreenHelpStep } from './ScreenHelp'
import { useSpotlight } from './useSpotlight'
import { useGuideSequence, useGuideStep } from './useGuideSequence'
import { GuideFieldPicker } from './GuideFieldPicker'
import { GuideStepContent } from './GuideStepContent'

const identify = (step: ScreenHelpStep) => step.targetId

function visible(element: HTMLElement) {
  if (!element.isConnected || element.closest('[hidden], [inert], [aria-hidden="true"]')) return false
  const style = getComputedStyle(element)
  return style.display !== 'none' && style.visibility !== 'hidden' && element.getClientRects().length > 0
}

function resolveTarget(trigger: HTMLElement, step: ScreenHelpStep) {
  const root = step.scope === 'shell' ? trigger.closest('.app-shell')
    : trigger.closest('[data-guide-scope], [role="dialog"]') ?? document.getElementById('main-content')
  if (!root) return null
  const selector = step.selector ?? `[${step.attribute ?? 'data-guide-target'}="${step.targetId}"]`
  const matches = [...(root.matches(selector) ? [root as HTMLElement] : []), ...root.querySelectorAll<HTMLElement>(selector)].filter(visible)
  // Repeated row controls are explained by their containing list, never an arbitrary row.
  if (!matches.length || matches.length !== 1 && !step.firstMatch) return null
  const element = matches[0]
  if (step.anchor !== 'start') return element
  // Lists use their columns and first three rows; useSpotlight bounds this preview.
  const table = element.matches('table') ? element : [...element.querySelectorAll<HTMLElement>('table')].find(visible)
  if (table) return table
  const label = element.getAttribute('aria-labelledby')?.split(' ')[0]
  const heading = label ? document.getElementById(label) : null
  const start = heading && element.contains(heading) ? heading
    : element.querySelector<HTMLElement>(':scope > .surface-header, :scope > header, :scope > h2, :scope > h3, thead')
  return start && visible(start) ? start : element
}

/** Explanation only: advancing a step never activates a save guide or a business control. */
export function ScreenWalkthrough({ title, steps, disabled, triggerRef }: {
  title: string; steps: readonly ScreenHelpStep[]; disabled?: boolean; triggerRef?: Ref<HTMLButtonElement>
}) {
  const [trigger, setTrigger] = useState<HTMLButtonElement | null>(null)
  const [ownerDialog, setOwnerDialog] = useState<HTMLElement | null>(null)
  const card = useRef<HTMLElement | null>(null)
  const [session, setSession] = useState<readonly ScreenHelpStep[] | null>(null)
  const [sessionId, setSessionId] = useState(0)
  const [folded, setFolded] = useState(false)
  const [target, setTarget] = useState<HTMLElement | null>(null)
  const [covered, setCovered] = useState(false)
  const sequence = useGuideSequence(session ?? [], String(sessionId), identify)
  const { index, step } = sequence
  const position = useSpotlight(session && !covered && !folded ? target : null, card, true, step?.presentation)
  const close = () => { setSession(null); trigger?.focus() }

  useGuideStep(step, target, () => sequence.last ? close() : sequence.next(), Boolean(session && !covered && !folded))

  useEffect(() => {
    const other = (event: Event) => {
      if ((event as CustomEvent).detail !== trigger) setSession(null)
    }
    document.addEventListener('fgc-screen-help-open', other)
    const unsubscribe = subscribeSettlementEntry(() => { if (settlementEntry()) setSession(null) })
    return () => { document.removeEventListener('fgc-screen-help-open', other); unsubscribe() }
  }, [trigger])

  useLayoutEffect(() => {
    if (card.current) card.current.scrollTop = 0
    if (session) card.current?.querySelector<HTMLButtonElement>('[data-walkthrough-close]')?.focus({ preventScroll: true })
  }, [session])
  useLayoutEffect(() => { if (card.current) card.current.scrollTop = 0 }, [index])

  useEffect(() => {
    if (!session) return
    let frame = 0
    const isCovered = () => {
      const topDialog = [...document.querySelectorAll<HTMLElement>('[role="dialog"], [aria-modal="true"]')].filter(visible).at(-1)
      return Boolean(topDialog && topDialog !== ownerDialog)
    }
    const refresh = () => {
      setCovered(isCovered())
      setTarget(step && trigger ? resolveTarget(trigger, step) : null)
    }
    refresh()
    const observer = new MutationObserver(() => {
      cancelAnimationFrame(frame)
      frame = requestAnimationFrame(refresh)
    })
    observer.observe(document.body, { childList: true, subtree: true, attributes: true,
      attributeFilter: ['hidden', 'inert', 'aria-hidden', 'class', 'style', 'data-guide-target', 'data-guide-feature'] })
    const escape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || isCovered()) return
      event.preventDefault(); event.stopImmediatePropagation()
      setSession(null); trigger?.focus()
    }
    document.addEventListener('keydown', escape, true)
    window.addEventListener('resize', refresh)
    return () => { observer.disconnect(); cancelAnimationFrame(frame); document.removeEventListener('keydown', escape, true); window.removeEventListener('resize', refresh) }
  }, [session, step, ownerDialog, trigger])

  return <>
    <button type="button" className="button button-secondary screen-help-trigger" disabled={disabled} aria-expanded={Boolean(session)}
      ref={triggerRef}
      onClick={event => {
        if (session) { close(); return }
        const current = event.currentTarget
        setTrigger(current); setOwnerDialog(current.closest('[role="dialog"]'))
        document.dispatchEvent(new CustomEvent('fgc-screen-help-open', { detail: current }))
        closeSettlementGuide()
        const available = steps.filter(item => resolveTarget(current, item))
        setSessionId(value => value + 1); setFolded(false); setCovered(false); setSession(available)
        setTarget(available[0] ? resolveTarget(current, available[0]) : null)
      }}>
      <svg className="screen-help-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><circle cx="12" cy="12" r="9" /><path d="M9.5 9a2.5 2.5 0 0 1 5 0c0 1.5-2.5 2-2.5 4m0 3h.01" /></svg>
      도움말
    </button>
    {session && !covered && createPortal(<>
      {!folded && step?.openControl && <GuideFieldPicker key={step.targetId} target={target} />}
      {!folded && position.box && <div className="payment-guide-spotlight screen-step-spotlight" style={position.box} aria-hidden="true" />}
      <section ref={card} className="payment-guide-card screen-step-guide" aria-label={`${title} 도움말`}
        data-help-presentation={Boolean(step?.presentation)}
        data-help-target={step?.targetId} data-guide-anchored={Boolean((target || position.box) && !folded)}
        style={{ maxHeight: 'min(560px, 65dvh)', overflowY: 'auto', ...position.card }}>
        <header className="payment-guide-header">
          <img className="payment-guide-mascot" src={guideMascot} width="40" height="40" alt="" />
          <span className="screen-step-count">화면 요소 안내{session.length > 0 && ` · ${index + 1}/${session.length}`}</span>
          <Button variant="ghost" onClick={() => setFolded(!folded)}>{folded ? '펼치기' : '접기'}</Button>
          <Button variant="ghost" data-walkthrough-close onClick={close}>종료</Button>
        </header>
        {!folded && (step ? <>
          <GuideStepContent step={step} index={index} total={session.length}>
            {!target && <p role="status">화면 구성이 바뀌어 이 요소가 숨겨졌습니다. 다음 설명을 보거나, 필요한 탭·상세를 연 뒤 도움말을 다시 열어 주세요.</p>}
          </GuideStepContent>
          {!step.concise && index === 0 && <p className="screen-step-context">현재 표시된 요소를 안내합니다. 다른 탭이나 상세에서도 도움말을 다시 열어 보세요.</p>}
          <div className="payment-guide-actions">
            <Button variant="secondary" disabled={index === 0} onClick={sequence.previous}>이전</Button>
            <Button className="screen-step-next" disabled={step.requireAction && target?.getAttribute('data-guide-completed') !== 'true'}
              onClick={() => sequence.last ? close() : sequence.next()}>{step.requireAction && target?.getAttribute('data-guide-completed') !== 'true' ? '실행 버튼을 눌러주세요' : sequence.last ? '안내 마치기' : '다음'}</Button>
          </div>
        </> : <>
          <h2>안내할 화면을 준비하고 있어요</h2>
          <p>화면이 로딩되었는지 확인하거나 목록에서 상세를 선택한 뒤 도움말을 다시 열어 주세요.</p>
          <div className="payment-guide-actions"><Button className="screen-step-next" onClick={close}>닫기</Button></div>
        </>)}
      </section>
    </>, ownerDialog ?? document.body)}
  </>
}
