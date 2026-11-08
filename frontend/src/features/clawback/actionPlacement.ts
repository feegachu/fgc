import { createContext } from 'react'

export const ClawbackActionPlacement = createContext<{
  revised: boolean; calculation: HTMLElement | null; document: HTMLElement | null
  roleCode?: string; refreshing?: boolean; onRefresh?: () => Promise<unknown>
}>({ revised: false, calculation: null, document: null })
