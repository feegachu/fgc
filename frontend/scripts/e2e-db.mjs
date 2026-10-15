import { execFileSync } from 'node:child_process'
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'

// Only the disposable database carrying our label may be restored. Never use the local application DB.
export function inspectDatabase() {
  const container = process.env.FGC_E2E_DB_CONTAINER
  if (!container || !/^[a-zA-Z0-9_-]+$/.test(container)) throw new Error('FGC_E2E_DB_CONTAINER is required')
  const [metadata] = JSON.parse(execFileSync('docker', ['inspect', container], { encoding: 'utf8' }))
  if (
    metadata.Config.Labels?.['com.fgc.e2e'] !== '418' ||
    !metadata.Config.Env.includes('POSTGRES_DB=fgc_e2e') ||
    !metadata.Config.Env.includes('POSTGRES_USER=fgc_e2e')
  ) {
    throw new Error('Refusing reset: expected disposable com.fgc.e2e=418 / fgc_e2e database')
  }
  const port = process.env.FGC_E2E_DB_PORT ?? '55418'
  if (!metadata.NetworkSettings.Ports['5432/tcp']?.some((binding) => binding.HostPort === port))
    throw new Error(`FGC_E2E_DB_PORT=${port} is not mapped to the labeled E2E container`)
  return container
}
export function databaseCommand(command, input) {
  const container = inspectDatabase()
  return execFileSync('docker', ['exec', '-i', container, ...command], {
    input,
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
    timeout: 60_000,
  })
}
export function sql(statement) {
  return databaseCommand(
    ['psql', '-X', '-U', 'fgc_e2e', '-d', 'fgc_e2e', '-v', 'ON_ERROR_STOP=1', '-At'],
    statement,
  ).trim()
}
export function snapshot() {
  mkdirSync('test-results', { recursive: true })
  writeFileSync(
    'test-results/demo-seed.sql',
    databaseCommand([
      'pg_dump',
      '-U',
      'fgc_e2e',
      '-d',
      'fgc_e2e',
      '--clean',
      '--if-exists',
      '--no-owner',
      '--no-privileges',
    ]),
  )
}
export function resetDatabase() {
  const active = sql("SELECT count(*) FROM fgc.validation_run WHERE status IN ('CREATED', 'RUNNING')")
  if (active !== '0') throw new Error(`Refusing reset while ${active} validation runs are active; inspect backend log`)
  sql(readFileSync('test-results/demo-seed.sql', 'utf8'))
}
