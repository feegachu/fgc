import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import ts from 'typescript'

const frontend = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const root = path.join(frontend, 'src')
const manifest = path.join(root, 'features/assistant/feature-targets.generated.json')
const screensManifest = path.join(root, 'features/assistant/screens.generated.json')
const tags = new Set(['button', 'input', 'select', 'textarea', 'form', 'section', 'table', 'a', 'Button', 'SearchSelect', 'ContextLink', 'Link'])
const entries = []
const missing = []
function walk(dir) {
  for (const file of fs.readdirSync(dir, { withFileTypes: true })) {
    const absolute = path.join(dir, file.name)
    if (file.isDirectory()) { walk(absolute); continue }
    if (!absolute.endsWith('.tsx') || /\.(test|spec)\./.test(absolute)) continue
    const relative = path.relative(root, absolute).replaceAll('\\', '/')
    if (relative.startsWith('features/assistant/')) continue
    const text = fs.readFileSync(absolute, 'utf8')
    const source = ts.createSourceFile(relative, text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX)
    function visit(node) {
      if (ts.isJsxOpeningElement(node) || ts.isJsxSelfClosingElement(node)) {
        const tag = node.tagName.getText(source)
        if (tags.has(tag)) {
          const attrs = node.attributes.properties.filter(ts.isJsxAttribute)
          const id = attrs.find((attr) => attr.name.getText(source) === 'data-guide-feature')?.initializer
          if (!id || !ts.isStringLiteral(id)) missing.push(`${relative}:${source.getLineAndCharacterOfPosition(node.pos).line + 1}`)
          else entries.push({ id: id.text, file: relative, element: tag })
        }
      }
      ts.forEachChild(node, visit)
    }
    visit(source)
  }
}
walk(root)
const ids = entries.map((entry) => entry.id)
if (missing.length || new Set(ids).size !== ids.length) {
  throw new Error(`Guide feature coverage failed: missing=${missing.join(',')}; duplicate=${ids.filter((id, index) => ids.indexOf(id) !== index).join(',')}`)
}
const content = `${JSON.stringify(entries.sort((a, b) => a.id.localeCompare(b.id, 'en')), null, 2)}\n`
const screenSource = ts.createSourceFile('screens.ts', fs.readFileSync(path.join(root, 'app/screens.ts'), 'utf8'), ts.ScriptTarget.Latest, true)
const screens = []
function readScreens(node) {
  if (ts.isVariableDeclaration(node) && node.name.getText(screenSource) === 'screens' && node.initializer && ts.isArrayLiteralExpression(node.initializer)) {
    for (const element of node.initializer.elements) {
      if (!ts.isObjectLiteralExpression(element)) throw new Error('Screens must be statically registered')
      screens.push(Object.fromEntries(element.properties.map((prop) => {
        if (!ts.isPropertyAssignment(prop) || !ts.isStringLiteral(prop.initializer)) throw new Error('Screen metadata must use string literals')
        return [prop.name.getText(screenSource), prop.initializer.text]
      })))
    }
  }
  ts.forEachChild(node, readScreens)
}
readScreens(screenSource)
if (!screens.length || new Set(screens.map((screen) => screen.path)).size !== screens.length) throw new Error('Invalid screen route coverage')
for (const [file, generated] of [[manifest, content], [screensManifest, `${JSON.stringify(screens, null, 2)}\n`]]) {
  if (process.argv.includes('--write')) fs.writeFileSync(file, generated)
  else if (fs.readFileSync(file, 'utf8') !== generated) throw new Error('Guide registry is stale. Run npm run generate:guide-features.')
}
console.log(`Guide features: ${entries.length} registered controls/regions; no uncovered supported JSX elements.`)
