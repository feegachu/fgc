import type { ReactNode } from 'react'
import { useEffect, useState } from 'react'
import type { ScreenHelpStep } from './ScreenHelp'

function LiveDescription({ selector, fallback }: { selector: string; fallback: string }) {
  const [description, setDescription] = useState(fallback)
  useEffect(() => {
    const element = document.querySelector<HTMLElement>(selector)
    const update = () => setDescription(element?.dataset.guideDescription || fallback)
    update()
    if (!element) return
    const observer = new MutationObserver(update)
    observer.observe(element, { attributes: true, attributeFilter: ['data-guide-description'] })
    return () => observer.disconnect()
  }, [selector, fallback])
  return <p className="screen-step-summary" aria-live="polite">{description}</p>
}

/** Shared copy and layout for page help and the end-to-end workflow. */
export function GuideStepContent({ step, index, total, group, children }: {
  step: Pick<ScreenHelpStep, 'title' | 'instruction'> & Partial<Pick<ScreenHelpStep, 'group' | 'concise' | 'interaction' | 'details' | 'liveDescriptionSelector'>>
  index: number
  total: number
  group?: string
  children?: ReactNode
}) {
  return <>
    <progress className="screen-step-progress" max={total} value={index + 1} aria-label="도움말 진행" />
    <p className="screen-step-group">{group ?? step.group}</p>
    <span className="visually-hidden" role="status">{index + 1}/{total} {step.title}</span>
    <h2>{step.title}</h2>
    {step.liveDescriptionSelector ? <LiveDescription selector={step.liveDescriptionSelector} fallback={step.instruction} />
      : step.concise ? <p className="screen-step-summary"><span>{step.instruction}</span><span>{step.interaction}</span></p> : <p>{step.instruction}</p>}
    {children}
    {!step.concise && <>
      {!!step.details?.length && <dl className="screen-step-details">{step.details.map(item => <div key={item.label}><dt>{item.label}</dt><dd>{item.text}</dd></div>)}</dl>}
      {step.interaction && <p className="screen-step-interaction"><strong>화면에서 할 수 있는 일</strong>{step.interaction}</p>}
    </>}
  </>
}
