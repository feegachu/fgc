import { spawn } from 'node:child_process'
import { createWriteStream, mkdirSync, readdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { databaseCommand } from './e2e-db.mjs'
import { waitForBackend } from './check-backend.mjs'

databaseCommand(['pg_isready', '-U', 'fgc_e2e', '-d', 'fgc_e2e'])
const jars = readdirSync('../build/libs').filter((name) => name.endsWith('.jar') && !name.endsWith('-plain.jar'))
if (jars.length !== 1)
  throw new Error('Build the backend first: ./gradlew bootJar (exactly one executable jar required)')
const port = process.env.FGC_E2E_DB_PORT ?? '55418'
if (!/^\d+$/.test(port)) throw new Error('FGC_E2E_DB_PORT must be numeric')
mkdirSync('e2e-artifacts', { recursive: true })
const log = createWriteStream('e2e-artifacts/backend.log')
const child = spawn('java', ['-jar', resolve('../build/libs', jars[0])], {
  windowsHide: true,
  env: {
    ...process.env,
    SPRING_PROFILES_ACTIVE: 'e2e',
    SERVER_PORT: '18081',
    SPRING_DATASOURCE_URL: `jdbc:postgresql://127.0.0.1:${port}/fgc_e2e?currentSchema=fgc`,
    SPRING_DATASOURCE_USERNAME: 'fgc_e2e',
    SPRING_DATASOURCE_PASSWORD: process.env.FGC_E2E_DB_PASSWORD ?? 'fgc_e2e_only',
    SPRING_FLYWAY_LOCATIONS: 'classpath:db/migration,classpath:db/demo',
    FGC_BATCH_DAILY_CHANGED_CONTRACT_ENABLED: 'false',
    FGC_CONTRACT_STATUS_FOLLOW_UP_ENABLED: 'false',
  },
})
child.stdout.pipe(log)
child.stderr.pipe(log)
child.on('error', (error) => {
  console.error(error)
  process.exitCode = 1
})
child.on('exit', (code) => {
  process.exitCode = code ?? 1
})
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => child.kill(signal))
try {
  await waitForBackend('http://127.0.0.1:18081/actuator/health', 180_000, () => child.exitCode === null)
} catch (error) {
  console.error(error.message)
  child.kill()
  process.exitCode = 1
}
