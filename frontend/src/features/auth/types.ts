import type { MeResponse } from '../../lib/api/client'

export type Permission =
  | 'canProcess'
  | 'canViewAuditLog'
  | 'canHandleException'
  | 'canReverseJournal'
  | 'canFinalizeValidation'

export type AuthUser = Required<MeResponse>
