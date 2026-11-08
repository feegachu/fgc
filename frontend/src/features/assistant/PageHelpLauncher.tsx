import { useEffect } from 'react'
import { useLocation, useNavigate } from 'react-router'
import type { ShellUser } from '../../app/shell/Sidebar'
import { canOpen, screenFor } from '../../app/screens'
import { toast } from '../../stores/toasts'
import { useWorkspaceStore } from '../../stores/workspace'
import { useWorkflowGuide } from './workflow-state'
import { pageHelpDestination } from './page-help-targets'

function waitForPageHelp(screenId: string, pathname: string, signal: AbortSignal) {
  return new Promise<HTMLButtonElement>((resolve, reject) => {
    const finish = (button?: HTMLButtonElement) => {
      observer.disconnect(); clearTimeout(timer); signal.removeEventListener('abort', abort)
      if (button) resolve(button)
      else reject(new Error('PAGE_HELP_UNAVAILABLE'))
    }
    const read = () => {
      const main = document.getElementById('main-content')
      if (main?.hasAttribute('data-guide-pathname') && main.dataset.guidePathname !== pathname) return
      if (document.querySelector('[aria-modal="true"]')) return
      const buttons = [...document.querySelectorAll<HTMLButtonElement>(`#main-content [data-screen-help="${screenId}"]`)]
        .filter(button => !button.disabled && !button.closest('[role="dialog"], [hidden], [inert]') && button.getClientRects().length)
      const primary = buttons.find(button => !button.dataset.screenHelpVariant || button.dataset.screenHelpVariant === 'reader') ?? buttons[0]
      if (primary) finish(primary)
    }
    const observer = new MutationObserver(read)
    const timer = window.setTimeout(() => finish(), 10000)
    const abort = () => finish()
    signal.addEventListener('abort', abort, { once: true })
    observer.observe(document.body, { childList: true, subtree: true, attributes: true })
    if (signal.aborted) finish()
    else read()
  })
}

/** Navigate, then press the same Help button the user can press on that page. */
export function PageHelpLauncher({ user }: { user: ShellUser }) {
  const request = useWorkflowGuide(state => state.request)
  const navigate = useNavigate()
  const location = useLocation()
  useEffect(() => {
    if (!request) return
    const controller = new AbortController()
    void (async () => {
      try {
        let destination = pageHelpDestination(request.id, location.pathname, location.search, useWorkspaceStore.getState().month)
        if (!destination) throw new Error('UNKNOWN_HELP_DESTINATION')
        if (!canOpen(destination.screen, user)) {
          const menu = screenFor(destination.screen.menu)
          if (!menu || !canOpen(menu, user)) throw new Error('PERMISSION_DENIED')
          const month = useWorkspaceStore.getState().month
          destination = { pathname: menu.path, screen: menu, href: menu.path + (month ? '?month=' + month : '') }
        }
        if (destination.href !== location.pathname + location.search) await navigate(destination.href)
        if (controller.signal.aborted) return
        const button = await waitForPageHelp(destination.screen.id, destination.pathname, controller.signal)
        if (!controller.signal.aborted) {
          if (button.getAttribute('aria-expanded') === 'true') return
          button.click()
        }
      } catch {
        if (!controller.signal.aborted) toast('해당 화면의 도움말을 열지 못했습니다. 화면과 선택 대상을 확인해 주세요.', 'warning')
      } finally {
        if (useWorkflowGuide.getState().request === request) useWorkflowGuide.getState().clear()
      }
    })()
    return () => controller.abort()
    // A request captures its source page. Destination navigation must not restart it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [request, navigate, user])
  return null
}
