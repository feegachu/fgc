import { useEffect, useId } from 'react'
import { create } from 'zustand'

export const useUnsavedWorkStore = create<{ owners: Record<string, boolean>; mark: (id: string, dirty: boolean) => void }>((set) => ({
  owners: {},
  mark: (id, dirty) => set((state) => {
    const owners = { ...state.owners }
    if (dirty) owners[id] = true
    else delete owners[id]
    return { owners }
  }),
}))

export function useUnsavedWork(dirty: boolean, revision?: string) {
  const owner = useId()
  useEffect(() => {
    useUnsavedWorkStore.getState().mark(owner, dirty)
    return () => useUnsavedWorkStore.getState().mark(owner, false)
  }, [owner, dirty, revision])
  return () => useUnsavedWorkStore.getState().mark(owner, false)
}
