import { readFileSync } from 'node:fs'
const definitions = JSON.parse(readFileSync(new URL('../src/features/assistant/guide/screen-help.json', import.meta.url), 'utf8'))
const source = readFileSync(new URL('../src/features/schedule/SchedulePreviewForm.tsx', import.meta.url), 'utf8')
const guides = definitions.filter(d => d.variant === 'preview')
if (guides.length !== 2 || new Set(guides.map(d => d.screenId)).size !== 2) throw Error('Preview screen registration missing or duplicated')
for (const guide of guides) {
  const targets = guide.steps.map(s => s.targetId)
  if (new Set(targets).size !== targets.length) throw Error('Duplicate preview step target')
  for (const target of targets) {
    if (source.split(`data-guide-target="${target}"`).length - 1 !== 1) throw Error(`Missing or duplicate preview target: ${target}`)
  }
}
console.log('Preview guide registrations and scoped targets verified')
