/** Demonstrate read-only tree selection using the same handlers as the user. */
export function previewTree(kind: 'reconciliation' | 'ledger') {
  const rootSelector = kind === 'reconciliation' ? '[data-guide-target="reconciliation.history"]' : '.ledger-contracts'
  const resultSelectors = kind === 'reconciliation' ? '[aria-label="실행 요약"], [data-guide-target="reconciliation.results"]' : '.ledger-contract-summary, .ledger-journals'
  let timer = 0, cancelled = false
  const root = () => document.querySelector<HTMLElement>(rootSelector)
  const pulse = () => document.querySelectorAll(resultSelectors).forEach(element => {
    element.classList.remove('guide-selection-updated')
    void (element as HTMLElement).offsetWidth
    element.classList.add('guide-selection-updated')
  })
  let previousButton: HTMLButtonElement | null = null
  const selectNextReconciliation = () => {
    const buttons = [...(root()?.querySelectorAll<HTMLButtonElement>('.hierarchy-select') ?? [])]
    const nextIndex = previousButton === null ? 0 : buttons.indexOf(previousButton) + 1
    const button = buttons[nextIndex]
    if (!button) return false
    previousButton = button
    button.click()
    const item = button.closest('[role="treeitem"]')
    if (item?.getAttribute('aria-expanded') === 'false') {
      item.querySelector<HTMLButtonElement>(':scope > .hierarchy-row > .hierarchy-toggle')?.click()
    }
    return true
  }
  const tasks = [
    () => root()?.querySelector<HTMLButtonElement>('.ledger-contract-select')?.click(),
    () => root()?.querySelector<HTMLButtonElement>('.ledger-journal-select')?.click(),
    () => root()?.querySelectorAll<HTMLButtonElement>('.ledger-journal-select')[1]?.click(),
  ]
  let index = 0, attempts = 0
  const tick = () => {
    if (cancelled) return
    const loading = document.querySelector(`${rootSelector}[aria-busy="true"], .reco-react-results[aria-busy="true"], .ledger-explorer-right[aria-busy="true"]`)
    if ((!root() || loading) && attempts++ < 20) { timer = window.setTimeout(tick, 500); return }
    attempts = 0
    if (!root() || loading) return
    if (kind === 'reconciliation') {
      if (!selectNextReconciliation()) return
    } else {
      if (index >= tasks.length) return
      tasks[index++]()
    }
    if (kind === 'ledger') pulse()
    timer = window.setTimeout(tick, kind === 'reconciliation' ? 3000 : 2200)
  }
  // Manual interaction takes over immediately; never fight a user's tree selection.
  const stop = (event: Event) => { if (event.isTrusted && root()?.contains(event.target as Node)) { cancelled = true; clearTimeout(timer) } }
  document.addEventListener('pointerdown', stop, true)
  document.addEventListener('keydown', stop, true)
  timer = window.setTimeout(tick, 800)
  return () => {
    cancelled = true; clearTimeout(timer)
    document.removeEventListener('pointerdown', stop, true)
    document.removeEventListener('keydown', stop, true)
    document.querySelectorAll(resultSelectors).forEach(element => element.classList.remove('guide-selection-updated'))
  }
}

/** Explain the two controls before starting the automatic selection demo. */
export function prepareReconciliationTree() {
  const root = document.querySelector<HTMLElement>('[data-guide-target="reconciliation.history"]')
  const expanded = [...(root?.querySelectorAll<HTMLButtonElement>('[role="treeitem"][aria-expanded="true"] > .hierarchy-row > .hierarchy-toggle') ?? [])].reverse()
  expanded.forEach(button => button.click())
  root?.classList.add('guide-tree-controls')
  return () => root?.classList.remove('guide-tree-controls')
}
