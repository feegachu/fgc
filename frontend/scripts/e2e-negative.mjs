import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:net'
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const evidence = join(root, 'e2e-artifacts', 'negative')
mkdirSync(evidence, { recursive: true })
const workspace = mkdtempSync(join(evidence, '.workspace-'))
const sourcePath = 'src/features/validation/ValidationRunDetailPage.tsx'
const testPath = 'src/features/validation/validation.test.tsx'
const testName = 'SETTLEMENT 는 확정할 수 없고 사유를 안내한다'
const source = readFileSync(join(root, sourcePath))
const digest = (bytes) => createHash('sha256').update(bytes).digest('hex')
const summary = { passed: false, sourceSha256: digest(source), testSha256: digest(readFileSync(join(root, testPath))) }

function run(name, args) {
  const result = spawnSync(process.execPath, args, {
    cwd: root,
    encoding: 'utf8',
    timeout: 120_000,
    maxBuffer: 10 * 1024 * 1024,
    env: { ...process.env, NO_COLOR: '1', FORCE_COLOR: '0' },
  })
  writeFileSync(join(evidence, `${name}.log`), `${result.stdout ?? ''}${result.stderr ?? ''}`)
  assert.ifError(result.error)
  assert.equal(result.signal, null, `${name}: child terminated by signal`)
  return result
}

function permissionTest(name, expectedStatus) {
  const report = join(evidence, `${name}.json`)
  // Remove a previous report so a failed runner cannot reuse stale evidence.
  rmSync(report, { force: true })
  const result = run(name, [
    join(root, 'node_modules/vitest/vitest.mjs'), 'run', testPath,
    '--root', workspace, '--config', join(root, 'vite.config.ts'),
    '-t', `^.*${testName}$`, '--reporter=json', '--outputFile', report,
  ])
  assert.equal(result.status, expectedStatus === 'failed' ? 1 : 0, `${name}: unexpected exit code`)
  const data = JSON.parse(readFileSync(report, 'utf8'))
  assert.equal(data.testResults.length, 1, `${name}: expected one test file`)
  assert.equal(data.numFailedTests, expectedStatus === 'failed' ? 1 : 0)
  assert.equal(data.numPassedTests, expectedStatus === 'passed' ? 1 : 0)
  const executed = data.testResults.flatMap((suite) => suite.assertionResults)
    .filter((test) => test.status === 'passed' || test.status === 'failed')
  assert.equal(executed.length, 1, `${name}: expected exactly one executed test`)
  const [test] = executed
  assert.equal(test.title, testName)
  assert.equal(test.status, expectedStatus)
  if (expectedStatus === 'failed') {
    assert.match(test.failureMessages.join('\n'), /expect\(element\)\.toBeDisabled\(\)/)
    assert.match(test.failureMessages.join('\n'), /Received element is not disabled/)
  }
  console.log(`${name}: ${test.status} (exit ${result.status})${expectedStatus === 'failed' ? ' — expected toBeDisabled regression detected' : ''}`)
  return { exitCode: result.status, test: test.fullName, status: test.status, failureMessages: test.failureMessages }
}

try {
  // Only the disposable copy is mutated; tracked application and test files stay untouched.
  cpSync(join(root, 'src'), join(workspace, 'src'), { recursive: true })
  summary.baseline = permissionTest('permission-baseline', 'passed')
  const guard = "const enabled = canFinalize && status === 'COMPLETED' && passed && !mutation.isPending"
  const original = source.toString('utf8')
  assert.equal(original.split(guard).length, 2, 'Expected exactly one permission guard; update the mutation explicitly if code changes')
  writeFileSync(join(workspace, sourcePath), original.replace(guard, guard.replace('canFinalize && ', '')))
  summary.mutation = { removed: 'canFinalize &&', ...permissionTest('permission-mutated', 'failed') }
  writeFileSync(join(workspace, sourcePath), source)
  summary.restored = permissionTest('permission-restored', 'passed')

  // Reserve then release an ephemeral localhost port. A different process taking it
  // makes this check fail rather than accepting an unrelated timeout/HTTP error.
  const reservation = createServer()
  await new Promise((resolve, reject) => {
    reservation.once('error', reject)
    reservation.listen(0, '127.0.0.1', resolve)
  })
  const port = reservation.address().port
  await new Promise((resolve, reject) => reservation.close((error) => error ? reject(error) : resolve()))
  const url = `http://127.0.0.1:${port}/actuator/health`
  const timeout = 1000
  const absent = run('backend-unavailable', [join(root, 'scripts/check-backend.mjs'), url, String(timeout)])
  assert.equal(absent.status, 1, 'Missing backend must exit 1')
  const diagnostic = absent.stderr.trim()
  for (const expected of ['FGC E2E backend readiness failed:', url, `within ${timeout}ms (ECONNREFUSED)`, 'e2e-artifacts/backend.log', 'Docker/PostgreSQL and Flyway', 'backend may not have started.']) {
    assert.ok(diagnostic.includes(expected), `Missing readiness diagnostic: ${expected}`)
  }
  summary.backendUnavailable = { exitCode: absent.status, url, timeoutMs: timeout, diagnostic }
  console.log(`backend-unavailable: expected exit 1\n${diagnostic}`)
  summary.passed = true
} catch (error) {
  summary.error = error.stack ?? String(error)
  console.error(summary.error)
  process.exitCode = 1
} finally {
  summary.sourceUnchanged = digest(readFileSync(join(root, sourcePath))) === summary.sourceSha256
  summary.testUnchanged = digest(readFileSync(join(root, testPath))) === summary.testSha256
  if (!summary.sourceUnchanged || !summary.testUnchanged) {
    summary.passed = false
    process.exitCode = 1
  }
  writeFileSync(join(evidence, 'summary.json'), `${JSON.stringify(summary, null, 2)}\n`)
  const child = relative(evidence, workspace)
  assert.ok(child && !isAbsolute(child) && child !== '..' && !child.startsWith(`..${sep}`), 'Cleanup must stay inside evidence directory')
  rmSync(workspace, { recursive: true, force: true })
}
