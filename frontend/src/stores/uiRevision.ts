import { create } from 'zustand'

type UiRevision = 'before' | 'after'
const key = 'fgc.ui-revision.v1'

function initialRevision(): UiRevision {
  try {
    return localStorage.getItem(key) === 'after' ? 'after' : 'before'
  } catch {
    return 'before'
  }
}

export const useUiRevision = create<{
  revision: UiRevision
  setRevision: (revision: UiRevision) => void
}>((set) => ({
  revision: initialRevision(),
  setRevision: (revision) => {
    set({ revision })
    try {
      localStorage.setItem(key, revision)
    } catch {
      // The comparison still works when preferences cannot be saved.
    }
  },
}))
