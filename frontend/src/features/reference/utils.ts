import { useOutletContext } from 'react-router'
import type { ShellContext } from '../../app/shell/AppShell'
export function useReferenceDate() {
  const { month } = useOutletContext<ShellContext>()
  return month ? `${month}-01` : ''
}
export function present(value: unknown) {
  return value == null || value === '' ? '-' : String(value)
}
