import { spawnSync } from 'node:child_process'
import { cpSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { deepStrictEqual } from 'node:assert'

// A complete suite, including a clean seed snapshot, must pass three consecutive times.
// Separate invocations guarantee suite A→B→C rather than each test repeated in isolation.
const summary = []
let baseline
mkdirSync('e2e-artifacts', { recursive: true })
for (let round = 1; round <= 3; round++) {
  console.log(`\nFGC demo stability: round ${round}/3 (retries=0)\n`)
  const startedAt = new Date().toISOString()
  const result = spawnSync(
    process.execPath,
    ['node_modules/@playwright/test/cli.js', 'test', '--config', 'playwright.demo.config.ts'],
    {
      stdio: 'inherit',
      windowsHide: true,
    },
  )
  const directory = `e2e-artifacts/round-${round}`
  mkdirSync(directory, { recursive: true })
  for (const path of [
    'playwright-report/demo',
    'test-results',
    'e2e-artifacts/backend.log',
    'e2e-artifacts/demo.xml',
    'e2e-artifacts/outcomes.json',
  ]) {
    if (existsSync(path)) cpSync(path, `${directory}/${path.split('/').at(-1)}`, { recursive: true })
  }
  summary.push({ round, startedAt, finishedAt: new Date().toISOString(), exitCode: result.status })
  writeFileSync('e2e-artifacts/stability.json', JSON.stringify(summary, null, 2))
  if (result.status !== 0) process.exit(result.status ?? 1)
  const outcomes = JSON.parse(readFileSync('e2e-artifacts/outcomes.json', 'utf8'))
  if (!Object.keys(outcomes).length) throw new Error('No scenario outcomes recorded')
  if (baseline) deepStrictEqual(outcomes, baseline, `Round ${round} DB counts/amounts differ from round 1`)
  else baseline = outcomes
}
