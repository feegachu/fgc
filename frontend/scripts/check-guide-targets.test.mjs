import { test } from 'node:test'
import assert from 'node:assert/strict'
import { inspectTargets, inspectHelpEntries, validateHelpCatalog, validateTargets, catalogTargets, check } from './check-guide-targets.mjs'
test('a target used only by the imported payment JSON is required', () => {
  const required = catalogTargets([])
  const declarations = [...new Set(required)].filter(id => id !== 'payment.evidence').map(id => ({ id }))
  assert.match(validateTargets(required, declarations).join('\n'), /payment.evidence:.*found 0/)
})
test('missing and duplicate native or forwarded targets fail', () => {
  const source = '<><section data-guide-target="a"/><Button data-guide-target="a"/></>'
  const declarations = inspectTargets(source).map(id => ({ id }))
  assert.match(validateTargets(['a', 'b'], declarations).join('\n'), /a:.*found 2/)
  assert.match(validateTargets(['a', 'b'], declarations).join('\n'), /b:.*found 0/)
})
test('conditional targets checked, comments ignored', () => {
  assert.deepEqual(inspectTargets('<section data-guide-target={tab === "a" ? "a" : "b"} /> // data-guide-target="fake"'), ['a', 'b'])
})
test('current catalogs and source bindings pass', check)

test('screen and variant must belong to the same real JSX help entry', () => {
  assert.deepEqual(inspectHelpEntries('<><ScreenHelp variant="editor" screenId="POL-W01"/><ScreenHelp screenId="EXCP-W01" />{/* <ScreenHelp screenId="POL-W01" variant="management"/> */}</>'), [
    { variant: 'editor', screenId: 'POL-W01' }, { screenId: 'EXCP-W01' },
  ])
})
test('duplicate and empty screen help definitions fail', () => {
  const entry = { screenId: 'EXCP-W01', variant: 'action', steps: [{ targetId: 'exception.action' }] }
  assert.match(validateHelpCatalog([entry, entry]).join('\n'), /duplicate help entry/)
  assert.match(validateHelpCatalog([{ ...entry, steps: [] }]).join('\n'), /empty help steps/)
})
