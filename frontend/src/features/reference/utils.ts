import { useOutletContext } from 'react-router'
import type { ShellContext } from '../../app/shell/AppShell'
import { screenMessage } from '../../lib/screenText'
export function useReferenceDate() {
  const { month } = useOutletContext<ShellContext>()
  return month ? `${month}-01` : ''
}
export function present(value: unknown) {
  return screenMessage(value == null ? null : String(value))
}
