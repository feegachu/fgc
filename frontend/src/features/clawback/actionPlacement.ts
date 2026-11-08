import { createContext } from 'react'

export const ClawbackActionPlacement = createContext<{
  revised: boolean; calculation: HTMLElement | null; document: HTMLElement | null
}>({ revised: false, calculation: null, document: null })
