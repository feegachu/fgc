import ts from 'typescript'
import { readFileSync, readdirSync } from 'node:fs'
import { resolve, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
const root = resolve(fileURLToPath(new URL('..', import.meta.url)))
export function inspectTargets(source, file = 'screen.tsx') {
  const ast = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX)
  const targets = []
  function literals(node) {
    if (!node) return
    if (ts.isStringLiteral(node)) targets.push(node.text)
    else if (ts.isJsxExpression(node)) literals(node.expression)
    else if (ts.isConditionalExpression(node)) { literals(node.whenTrue); literals(node.whenFalse) }
  }
  function visit(node) {
    if (ts.isJsxAttribute(node) && node.name.getText(ast) === 'data-guide-target') literals(node.initializer)
    ts.forEachChild(node, visit)
  }
  visit(ast)
  return targets
}
export function inspectHelpEntries(source, file = 'screen.tsx') {
  const ast = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX)
  const entries = []
  function visit(node) {
    if ((ts.isJsxSelfClosingElement(node) || ts.isJsxOpeningElement(node)) && node.tagName.getText(ast) === 'ScreenHelp') {
      const entry = {}
      for (const attr of node.attributes.properties) {
        if (!ts.isJsxAttribute(attr)) continue
        const name = attr.name.getText(ast)
        const value = attr.initializer && ts.isJsxExpression(attr.initializer) ? attr.initializer.expression : attr.initializer
        if (['screenId', 'variant'].includes(name) && value && ts.isStringLiteral(value)) entry[name] = value.text
      }
      entries.push(entry)
    }
    ts.forEachChild(node, visit)
  }
  visit(ast)
  return entries
}
export function validateHelpCatalog(catalog) {
  const errors = [], keys = new Set()
  for (const entry of catalog) {
    const key = `${entry.screenId}:${entry.variant ?? ''}`
    if (keys.has(key)) errors.push(`${key}: duplicate help entry`)
    keys.add(key)
    if (!entry.steps.length) errors.push(`${key}: empty help steps`)
    if (new Set(entry.steps.map(step => step.targetId)).size !== entry.steps.length) errors.push(`${key}: duplicate catalog step target`)
  }
  return errors
}
export function validateTargets(required, declarations) {
  const errors = []
  const pattern = readFileSync(resolve(root, 'src/features/assistant/guide/catalog.ts'), 'utf8').match(/export const TARGET_ID = \/(.+)\/$/m)?.[1]
  if (!pattern) throw new Error('Missing shared TARGET_ID pattern')
  const validId = new RegExp(pattern)
  for (const id of new Set(required)) {
    if (!validId.test(id)) errors.push(`${id}: invalid target id`)
    const count = declarations.filter((entry) => entry.id === id).length
    if (count !== 1) errors.push(`${id}: expected 1 target declaration, found ${count}`)
  }
  return errors
}
function files(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => entry.isDirectory() ? files(resolve(dir, entry.name)) : [resolve(dir, entry.name)])
}
export function check() {
  const catalog = JSON.parse(readFileSync(resolve(root, 'src/features/assistant/guide/screen-help.json'), 'utf8'))
  const declarations = files(resolve(root, 'src')).filter((f) => f.endsWith('.tsx') && !/test|spec/.test(f)).flatMap((file) => inspectTargets(readFileSync(file, 'utf8'), file).map((id) => ({ id, file: relative(root, file) })))
  const required = catalog.flatMap((entry) => entry.steps.map((step) => step.targetId))
  // Parse the existing payment and settlement catalogs, not a manually copied target list.
  for (const path of ['src/features/assistant/catalog.ts', 'src/features/assistant/settlement/catalog.ts']) {
    const ast = ts.createSourceFile(path, readFileSync(resolve(root, path), 'utf8'), ts.ScriptTarget.Latest, true)
    function visit(node) {
      if (ts.isCallExpression(node) && node.expression.getText(ast) === 'step' && ts.isStringLiteral(node.arguments[0])) required.push(node.arguments[0].text)
      if (ts.isPropertyAssignment(node) && node.name.getText(ast) === 'target' && ts.isStringLiteral(node.initializer)) required.push(node.initializer.text)
      ts.forEachChild(node, visit)
    }
    visit(ast)
  }
  const errors = [...validateTargets(required, declarations), ...validateHelpCatalog(catalog)]
  const routedIds = [...readFileSync(resolve(root, 'src/app/screens.ts'), 'utf8').matchAll(/id: '([^']+)'/g)].map((match) => match[1])
  for (const id of new Set(routedIds)) {
    if (id !== 'TRAN-W02' && !catalog.some((entry) => entry.screenId === id && !entry.variant)) errors.push(`${id}: missing routed screen help`)
  }
  for (const entry of catalog) {
    const page = readFileSync(resolve(root, 'src/features', entry.page), 'utf8')
    if (!inspectHelpEntries(page, entry.page).some(usage => usage.screenId === entry.screenId && usage.variant === entry.variant)) errors.push(`${entry.screenId}:${entry.variant ?? ''}: missing exact help entry in ${entry.page}`)
  }
  if (errors.length) throw new Error(errors.join('\n'))
  console.log(`Guide targets: ${catalog.length} screen entries, ${new Set(required).size} target bindings checked`)
}
if (process.argv[1] === fileURLToPath(import.meta.url)) check()
