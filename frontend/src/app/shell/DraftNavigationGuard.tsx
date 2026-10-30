import { useEffect } from 'react'
import { useBlocker } from 'react-router'
import { useUnsavedWorkStore } from '../../stores/unsavedWork'
import { Modal } from '../../components/Modal'
import { useWorkspaceStore } from '../../stores/workspace'

/** One blocker for the workspace; tutorial navigation and browser back use the same guard. */
export function DraftNavigationGuard() {
  const blocker = useBlocker(({ currentLocation, nextLocation }) => {
    const current = new URLSearchParams(currentLocation.search)
    const next = new URLSearchParams(nextLocation.search)
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
  return <Modal open={blocker.state === 'blocked'} title="저장하지 않은 입력이 있어요" onClose={() => blocker.reset?.()}
    footer={<>
      <button data-guide-feature="app.shell.draft-navigation-guard.keep" type="button" onClick={() => blocker.reset?.()}>현재 입력 유지 · 돌아가기</button>
      <button data-guide-feature="app.shell.draft-navigation-guard.discard" type="button" onClick={() => blocker.proceed?.()}>입력을 버리고 이동</button>
    </>}>
    <p>화면이나 기준월을 바꾸면 저장하지 않은 입력을 잃을 수 있습니다. 입력을 유지하려면 돌아가서 먼저 저장해주세요.</p>
  </Modal>
}
