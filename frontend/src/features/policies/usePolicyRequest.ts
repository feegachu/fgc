import { useRef } from 'react'

/** Keep an uncertain request retry idempotent; edited input starts a new request. */
export function usePolicyRequest() {
  const attempt = useRef<{ body: string; key: string } | null>(null)
  return (request: unknown) => {
    const body = JSON.stringify(request)
    if (attempt.current?.body !== body) attempt.current = { body, key: crypto.randomUUID() }
    return attempt.current.key
  }
}
