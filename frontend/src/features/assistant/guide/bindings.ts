import type { GuideRuntime } from './runtime'

/** #728 owns rendering; this binding owns keyboard/DOM/context cancellation and listener cleanup. */
export function bindGuideRuntime<State>(
  runtime: GuideRuntime<State>,
  options: {
    root: HTMLElement
    subscribeContext: (changed: () => void) => () => void
    subscribeLogout: (logout: () => void) => () => void
  },
): () => void {
  const changed = () => runtime.contextChanged()
  const unsubscribeContext = options.subscribeContext(changed)
  const unsubscribeLogout = options.subscribeLogout(() => runtime.stop())
  const observer = new MutationObserver(changed)
  observer.observe(options.root, {
    subtree: true,
    childList: true,
    attributes: true,
    attributeFilter: ['hidden', 'inert', 'aria-hidden', 'style', 'class', 'data-guide-target'],
  })
  const escape = (event: KeyboardEvent) => {
    if (event.key === 'Escape' && !['IDLE', 'COMPLETED', 'ERROR'].includes(runtime.getSnapshot().status)) {
      event.preventDefault()
      event.stopPropagation()
      runtime.stop()
    }
  }
  options.root.ownerDocument.addEventListener('keydown', escape, true)
  let disposed = false
  return () => {
    if (disposed) return
    disposed = true
    unsubscribeContext()
    unsubscribeLogout()
    observer.disconnect()
    options.root.ownerDocument.removeEventListener('keydown', escape, true)
    runtime.dispose()
  }
}
