import { readFileSync } from 'node:fs'
import { expect, it } from 'vitest'

it('디자인 토큰(상태 24·중립 12)을 모두 Tailwind 매핑에 노출한다', () => {
  const source = readFileSync('src/styles/variables.css', 'utf8')
  const mapping = readFileSync('src/styles/tokens.js', 'utf8')
  const names = [...source.matchAll(/--((?:color-|space-|radius-|z-)[\w-]+)\s*:/g)].map((match) => match[1])
  expect(names.filter((name) => name.startsWith('color-status-'))).toHaveLength(24)
  expect(names.filter((name) => name.startsWith('color-neutral-'))).toHaveLength(12)
  for (const name of names) expect(mapping).toContain(`var(--${name})`)
})
