import { spawn } from 'node:child_process'
import { copyFileSync, createWriteStream, mkdirSync, readdirSync, statSync } from 'node:fs'
import { resolve } from 'node:path'
import { databaseCommand } from './e2e-db.mjs'
import { waitForBackend } from './check-backend.mjs'

databaseCommand(['pg_isready', '-U', 'fgc_e2e', '-d', 'fgc_e2e'])
const jars = process.env.FGC_E2E_JAR
  ? [resolve(process.env.FGC_E2E_JAR)]
  : readdirSync('../build/libs').filter((name) => name.endsWith('.jar') && !name.endsWith('-plain.jar')).map(name => resolve('../build/libs', name))
if (jars.length !== 1)
  throw new Error('Build the backend first: ./gradlew bootJar (exactly one executable jar required)')
if (!statSync(jars[0]).isFile()) throw new Error('FGC_E2E_JAR must point to an executable jar file')
const port = process.env.FGC_E2E_DB_PORT ?? '55418'
if (!/^\d+$/.test(port)) throw new Error('FGC_E2E_DB_PORT must be numeric')
mkdirSync('e2e-artifacts', { recursive: true })
// IntelliJ나 다음 Gradle 빌드가 원본 JAR를 교체해도 실행 중인 시험 서버는 같은 산출물을 사용한다.
const serverJar = resolve('e2e-artifacts/server.jar')
if (jars[0] !== serverJar) copyFileSync(jars[0], serverJar)
const log = createWriteStream('e2e-artifacts/backend.log')
const child = spawn('java', ['-jar', serverJar], {
  windowsHide: true,
  env: {
    ...process.env,
    SPRING_PROFILES_ACTIVE: 'e2e',
    // Paid provider calls belong to the explicit live-smoke workflow.
    OPENAI_API_KEY: '',
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
