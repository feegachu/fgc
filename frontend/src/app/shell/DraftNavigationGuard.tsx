import { useEffect } from 'react'
import { useBlocker, useLocation } from 'react-router'
import { screenFor } from '../screens'
import { useUnsavedWorkStore } from '../../stores/unsavedWork'
import { Modal } from '../../components/Modal'
import { Button } from '../../components/Button'
import { useWorkspaceStore } from '../../stores/workspace'

/** One blocker for the workspace; tutorial navigation and browser back use the same guard. */
export function DraftNavigationGuard() {
  const location = useLocation()
  const keepInput = () => {
    const current = screenFor(location.pathname)
    if (current && !useWorkspaceStore.getState().tabs.some((tab) => tab.id === current.id)) {
      useWorkspaceStore.getState().openTab({ id: current.id, title: current.title, icon: current.icon, href: location.pathname + location.search + location.hash })
      useWorkspaceStore.getState().markModified(current.id)
    }
    blocker.reset?.()
  }
  const blocker = useBlocker(({ currentLocation, nextLocation }) => {
    const current = new URLSearchParams(currentLocation.search)
    const next = new URLSearchParams(nextLocation.search)
    // Closing/changing an exception modal leaves unrelated editors intact.
    // Its own dirty action form must still block a direct URL/selection change.
    if (currentLocation.pathname === nextLocation.pathname && /\/exceptions$/.test(currentLocation.pathname)) {
      const selected = current.get('selected')
      const currentFilters = new URLSearchParams(current), nextFilters = new URLSearchParams(next)
      currentFilters.delete('selected')
      nextFilters.delete('selected')
      if (currentFilters.toString() === nextFilters.toString()
        && !Object.keys(useUnsavedWorkStore.getState().owners).some(owner => owner.startsWith(`exception:${selected}:`))) return false
    }
    if (currentLocation.pathname === nextLocation.pathname && !current.has('month') && next.get('month') === useWorkspaceStore.getState().month) {
      next.delete('month')
      if (next.toString() === current.toString()) return false
    }
    return Object.keys(useUnsavedWorkStore.getState().owners).length > 0 &&
      (currentLocation.pathname !== nextLocation.pathname || currentLocation.search !== nextLocation.search)
  })
  useEffect(() => {
    const beforeUnload = (event: BeforeUnloadEvent) => {
      if (Object.keys(useUnsavedWorkStore.getState().owners).length) { event.preventDefault(); event.returnValue = '' }
    }
    window.addEventListener('beforeunload', beforeUnload)
    return () => window.removeEventListener('beforeunload', beforeUnload)
  }, [])
  return <Modal open={blocker.state === 'blocked'} title="저장하지 않은 입력이 있습니다" onClose={keepInput}
    footer={<>
      <Button data-guide-feature="app.shell.draft-navigation-guard.keep" variant="secondary" onClick={keepInput}>돌아가기</Button>
      <Button data-guide-feature="app.shell.draft-navigation-guard.discard" onClick={() => blocker.proceed?.()}>이동</Button>
    </>}>
    <p>화면이나 기준월을 바꾸면 저장하지 않은 입력을 잃을 수 있습니다. 입력을 유지하려면 돌아가서 먼저 저장해주세요.</p>
  </Modal>
}
