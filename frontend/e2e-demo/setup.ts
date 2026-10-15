import { snapshot } from '../scripts/e2e-db.mjs'
import { mkdirSync, writeFileSync } from 'node:fs'

export default function setup() {
  snapshot()
  mkdirSync('e2e-artifacts', { recursive: true })
  writeFileSync('e2e-artifacts/outcomes.json', '{}')
}
