import { useSearchParams } from 'react-router'
export function useSearchParamsState<T extends Record<string, string>>(defaults: T) {
  const [params, setParams] = useSearchParams()
  const values = Object.fromEntries(
    Object.entries(defaults).map(([key, fallback]) => [key, params.get(key) ?? fallback]),
  ) as T
  function update(patch: Partial<T>, { resetPage = true, replace = false } = {}) {
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        if (resetPage) next.delete('page')
        Object.entries(patch).forEach(([key, value]) => {
          if (value === '' || value === undefined) next.delete(key)
          else next.set(key, value)
        })
        return next
      },
      { replace },
    )
  }
  function reset() {
    setParams((current) => {
      const next = new URLSearchParams(current)
      Object.keys(defaults).forEach((key) => next.delete(key))
      next.delete('page')
      return next
    })
  }
  return { values, update, reset, params }
}
