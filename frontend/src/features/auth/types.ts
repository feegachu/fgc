import type { MeResponse } from '../../lib/api/client'

export type Permission =
  | 'canProcess'
  | 'canViewAuditLog'
  | 'canHandleException'
  | 'canReverseJournal'
  | 'canFinalizeValidation'
  | 'canUseAgentWorkspace'

export type AuthUser = Required<Omit<MeResponse, 'agentId' | 'isAgent' | 'canUseAgentWorkspace'>>
  & Pick<MeResponse, 'agentId' | 'isAgent' | 'canUseAgentWorkspace'>
