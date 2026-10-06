import { readFileSync } from 'node:fs'
import { expect, it } from 'vitest'

it('1차 CSS 토큰과 레이아웃·공통 스타일을 그대로 유지한다', () => {
  for (const name of ['variables', 'reset', 'layout', 'components', 'utilities']) {
    expect(readFileSync(`src/styles/${name}.css`, 'utf8')).toBe(
      readFileSync(`../src/main/resources/static/css/common/${name}.css`, 'utf8'),
    )
  }
  const source = readFileSync('src/styles/variables.css', 'utf8')
  const mapping = readFileSync('src/styles/tokens.js', 'utf8')
  const names = [...source.matchAll(/--((?:color-|space-|radius-|z-)[\w-]+)\s*:/g)].map((match) => match[1])
  expect(names.filter((name) => name.startsWith('color-status-'))).toHaveLength(24)
  expect(names.filter((name) => name.startsWith('color-neutral-'))).toHaveLength(12)
  for (const name of names) expect(mapping).toContain(`var(--${name})`)
})
