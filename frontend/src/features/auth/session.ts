import type { QueryClient } from '@tanstack/react-query'
import { useSidebarStore } from '../../stores/sidebar'
import { useWorkspaceStore } from '../../stores/workspace'

export function clearSessionData(client: QueryClient) {
  client.clear()
  useWorkspaceStore.getState().clear()
  // 1차 로그인과 같이 이전 사용자의 fgc.* UI 설정도 제거한다.
  for (const name of ['localStorage', 'sessionStorage'] as const) {
    try {
      const storage = window[name]
      for (let index = storage.length - 1; index >= 0; index--) {
        const key = storage.key(index)
        if (key?.startsWith('fgc.')) storage.removeItem(key)
      }
    } catch {
      // Storage를 사용할 수 없어도 메모리 인증과 로그아웃은 동작한다.
    }
  }
  useSidebarStore.setState({
    collapsed: window.matchMedia?.('(max-width: 79.9375rem)').matches ?? false,
    openSections: [],
  })
}
