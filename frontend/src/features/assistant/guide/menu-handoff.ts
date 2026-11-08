import type { ScreenHelpStep } from './ScreenHelp'
import { useSidebarStore } from '../../../stores/sidebar'

export function menuStep(targetId: string, menuId: string, label: string, path: string): ScreenHelpStep {
  return {
    targetId, selector: `[data-guide-feature="navigation.menu.${menuId}"]`, scope: 'shell',
    title: `${label}로 이어가기`, group: '다음 업무', concise: true, details: [],
    instruction: `왼쪽의 ${label} 메뉴를 누르세요.`,
    interaction: '이동한 화면에서 안내를 이어갑니다.',
    presentation: { fixedCard: 'right' }, follow: { path, label },
    onEnter: () => {
      useSidebarStore.getState().setCollapsed(false, false)
      if (menuId !== 'excp-w01') useSidebarStore.getState().setSection('ledger', true)
    },
  }
}
