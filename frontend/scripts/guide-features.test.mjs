import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'

test('screen registry preserves disabled routes and rejects dynamic metadata', () => {
  const root = mkdtempSync(join(tmpdir(), 'fgc-guide-metadata-'))
  try {
    mkdirSync(join(root, 'app'))
    mkdirSync(join(root, 'features/assistant'), { recursive: true })
    writeFileSync(join(root, 'features/assistant/feature-instances.v1.json'), '{}')
    const run = (metadata) => {
      writeFileSync(join(root, 'app/screens.ts'), `const screens = [${metadata}]`)
      return spawnSync(process.execPath, [fileURLToPath(new URL('./guide-features.mjs', import.meta.url)), '--root', root, '--write'], { encoding: 'utf8' })
    }
    const valid = run("{ id: 'A', path: '/a', enabled: false }, { id: 'B', path: '/b', enabled: true }, { id: 'C', path: '/c' }")
    assert.equal(valid.status, 0, valid.stderr)
    assert.deepEqual(JSON.parse(readFileSync(join(root, 'features/assistant/screens.generated.json'), 'utf8')), [
      { id: 'A', path: '/a', enabled: false }, { id: 'B', path: '/b', enabled: true }, { id: 'C', path: '/c' },
    ])
    assert.notEqual(run("{ id: 'A', path: '/a', enabled: isEnabled() }").status, 0)
    assert.notEqual(run("{ id: 'A', path: '/a', enabled: 'false' }").status, 0)
    assert.notEqual(run("{ id: 'A', path: false }").status, 0)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})
