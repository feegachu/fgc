import fs from 'node:fs'
import path from 'node:path'
import { createHash } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import ts from 'typescript'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../src')
const folder = path.join(root, 'features/assistant')
const read = (name) => JSON.parse(fs.readFileSync(path.join(folder, name), 'utf8'))
const registry = read('feature-targets.generated.json')
const screens = read('screens.generated.json')
const workflows = [...read('workflow-guides.v1.json'), ...read('payment-guides.v1.json')]
const instances = read('feature-instances.v1.json')
const parse = (file) => ts.createSourceFile(file, fs.readFileSync(file, 'utf8'), ts.ScriptTarget.Latest, true, file.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS)
function resolve(from, specifier) {
  if (!specifier.startsWith('.')) return null
  const candidate = path.resolve(path.dirname(from), specifier)
  return [candidate, candidate + '.tsx', candidate + '.ts', path.join(candidate, 'index.tsx'), path.join(candidate, 'index.ts')]
    .find((file) => /\.tsx?$/.test(file) && fs.existsSync(file) && fs.statSync(file).isFile()) ?? null
}
function runtimeImport(node) {
  return ts.isImportDeclaration(node) && ts.isStringLiteral(node.moduleSpecifier) && !node.importClause?.isTypeOnly &&
    !(node.importClause?.namedBindings && ts.isNamedImports(node.importClause.namedBindings) && !node.importClause.name &&
      node.importClause.namedBindings.elements.length && node.importClause.namedBindings.elements.every((item) => item.isTypeOnly))
}
const routesFile = path.join(root, 'app/routes.tsx')
const routes = parse(routesFile)
const imports = new Map()
for (const node of routes.statements) if (runtimeImport(node)) {
  const target = resolve(routesFile, node.moduleSpecifier.text)
  if (node.importClause?.name) imports.set(node.importClause.name.text, target)
  const bindings = node.importClause?.namedBindings
  if (bindings && ts.isNamedImports(bindings)) for (const item of bindings.elements) if (!item.isTypeOnly) imports.set(item.name.text, target)
}
const pages = new Map()
function findPages(node) {
  if (ts.isVariableDeclaration(node) && node.name.getText(routes) === 'pages' && node.initializer && ts.isObjectLiteralExpression(node.initializer)) {
    for (const item of node.initializer.properties) if (ts.isPropertyAssignment(item) && ts.isStringLiteral(item.name)) {
      let file = ts.isIdentifier(item.initializer) ? imports.get(item.initializer.text) : null
      function lazy(child) {
        if (ts.isCallExpression(child) && child.expression.kind === ts.SyntaxKind.ImportKeyword && ts.isStringLiteral(child.arguments[0])) file = resolve(routesFile, child.arguments[0].text)
        ts.forEachChild(child, lazy)
      }
      if (!file) lazy(item.initializer)
      if (!file) throw new Error(`Cannot resolve screen module: ${item.name.text}`)
      pages.set(item.name.text, file)
    }
  }
  ts.forEachChild(node, findPages)
}
findPages(routes)
const modules = new Map()
const compact = (value) => value.replace(/\s+/g, ' ').trim()
// Only literal UI copy is collected. Identifiers, expressions and runtime values are never evaluated.
function literal(node) {
  if (!node) return ''
  if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) return node.text
  if (ts.isJsxExpression(node) || ts.isParenthesizedExpression(node)) return literal(node.expression)
  if (ts.isTemplateExpression(node)) return node.head.text + node.templateSpans.map((span) => span.literal.text).join(' ')
  if (ts.isConditionalExpression(node)) return [literal(node.whenTrue), literal(node.whenFalse)].filter(Boolean).join(' / ')
  return ''
}
function attr(node, name) {
  return node.attributes.properties.find((item) => ts.isJsxAttribute(item) && item.name.getText() === name)?.initializer
}
function visible(node) {
  if (ts.isJsxText(node)) return node.text
  if (ts.isJsxExpression(node)) return literal(node)
  if (ts.isJsxElement(node)) {
    if (literal(attr(node.openingElement, 'aria-hidden')) === 'true') return ''
    return node.children.map(visible).join(' ')
  }
  return ''
}
function load(file) {
  if (modules.has(file)) return modules.get(file)
  const source = parse(file)
  const result = { dependencies: [], labels: new Map(), instanceLabels: new Map(), enums: [] }
  modules.set(file, result)
  for (const node of source.statements) if (runtimeImport(node)) {
    const target = resolve(file, node.moduleSpecifier.text)
    if (target && !target.includes(`${path.sep}features${path.sep}assistant${path.sep}`) && !target.endsWith(`${path.sep}PaymentGuide.tsx`)) result.dependencies.push(target)
  }
  const labelsById = new Map()
  function labels(node) {
    if (ts.isJsxElement(node)) {
      const elementId = literal(attr(node.openingElement, 'id'))
      if (elementId) labelsById.set(elementId, compact(visible(node)))
    }
    if (ts.isJsxElement(node) && node.openingElement.tagName.getText() === 'label') {
      const id = literal(attr(node.openingElement, 'htmlFor'))
      if (id) labelsById.set(id, compact(visible(node)))
    }
    ts.forEachChild(node, labels)
  }
  labels(source)
  function remember(key, value) {
    if (key && value && /[가-힣]/.test(value)) result.instanceLabels.set(key.toLowerCase(), compact(value))
  }
  function visit(node) {
    if (ts.isObjectLiteralExpression(node)) {
      const props = new Map(node.properties.filter(ts.isPropertyAssignment).map((prop) => [prop.name.getText().replace(/^['"]|['"]$/g, ''), literal(prop.initializer)]))
      remember(props.get('id') || props.get('key'), props.get('label') || props.get('title'))
      for (const [key, value] of props) remember(key, value)
    }
    if (ts.isArrayLiteralExpression(node) && node.elements.length === 2) remember(literal(node.elements[0]), literal(node.elements[1]))
    if (ts.isCallExpression(node) && ['field', 'numeric'].includes(node.expression.getText())) remember(literal(node.arguments[0]), literal(node.arguments[1]))
    if (ts.isJsxOpeningElement(node) || ts.isJsxSelfClosingElement(node)) {
      const value = attr(node, 'data-guide-feature') ?? attr(node, 'guideId')
      let ids = literal(value) ? [literal(value)] : []
      let group = null
      if (['DraftInput', 'DraftSelect'].includes(node.tagName.getText())) {
        const fieldPath = attr(node, 'path')
        const expression = fieldPath && ts.isJsxExpression(fieldPath) ? fieldPath.expression : fieldPath
        const key = expression && ts.isBinaryExpression(expression) ? literal(expression.right) : literal(expression)
        remember(key, literal(attr(node, 'label')))
      }
      remember(literal(attr(node, 'scope')), literal(attr(node, 'title')))
      if (value && ts.isJsxExpression(value) && value.expression && ts.isCallExpression(value.expression) && value.expression.expression.getText() === 'guideFeatureId') {
        group = literal(value.expression.arguments[0])
        ids = (instances[group] ?? []).map((id) => `${group}.${id}`)
      }
      let label = literal(attr(node, 'aria-label')) || literal(attr(node, 'label')) || labelsById.get(literal(attr(node, 'id'))) || labelsById.get(literal(attr(node, 'aria-labelledby'))) || ''
      if (!label) {
        const aria = attr(node, 'aria-label')
        const expression = aria && ts.isJsxExpression(aria) ? aria.expression : null
        if (expression && ts.isCallExpression(expression)) label = literal(expression.arguments.at(-1))
      }
      if (!label) for (const spread of node.attributes.properties.filter(ts.isJsxSpreadAttribute)) {
        if (ts.isCallExpression(spread.expression) && spread.expression.arguments.length >= 3) label = literal(spread.expression.arguments.at(-1))
      }
      // A field wrapper owns its input label; never infer a label from an input value/placeholder.
      let parent = node.parent
      for (let depth = 0; !label && parent && depth < 4; depth++, parent = parent.parent) {
        if (ts.isJsxElement(parent) && parent.openingElement.tagName.getText() === 'Field') label = literal(attr(parent.openingElement, 'label'))
        if (ts.isJsxElement(parent) && parent.openingElement.tagName.getText() === 'label') label = visible(parent)
        if (!label && depth < 2 && ts.isJsxElement(parent)) {
          const sibling = parent.children.find((child) => ts.isJsxElement(child) && child.openingElement.tagName.getText() === 'label')
          if (sibling) label = visible(sibling)
        }
      }
      const tag = node.tagName.getText()
      if (!label && ['button', 'Button', 'Link', 'ContextLink', 'a', 'summary'].includes(tag) && ts.isJsxElement(node.parent)) label = visible(node.parent)
      if (!label && ['section', 'form', 'table'].includes(tag) && ts.isJsxElement(node.parent)) {
        const heading = node.parent.children.find((child) => ts.isJsxElement(child) && /^h[1-6]$/.test(child.openingElement.tagName.getText()))
        if (heading) label = visible(heading)
      }
      label = compact(label)
      // A shared dynamic component needs per-instance source labels; omit unresolved labels rather than inventing them.
      if (group) result.enums.push({ group, ids, tag })
      if (ids.length === 1 && label && label.length <= 100 && /[가-힣a-zA-Z]/.test(label)) result.labels.set(ids[0], { label, tag })
    }
    ts.forEachChild(node, visit)
  }
  visit(source)
  return result
}
function closure(file, visited = new Set()) {
  if (visited.has(file)) return visited
  visited.add(file)
  for (const dependency of load(file).dependencies) closure(dependency, visited)
  return visited
}
const targetIds = new Set(registry.map((entry) => entry.id))
const generated = []
for (const guide of workflows) {
  const locations = new Map()
  const routesForGuide = new Set([guide.route, ...guide.steps.map((step) => screens.find((screen) => screen.id === step.screenId)?.path)].filter(Boolean))
  for (const route of routesForGuide) {
    const screen = screens.find((entry) => entry.path === route)
    const file = pages.get(route)
    if (!screen || !file) continue
    const dependencies = [...closure(file)]
    const instanceLabels = new Map()
    for (const dependency of dependencies) for (const [key, label] of load(dependency).instanceLabels) instanceLabels.set(key, label)
    for (const dependency of dependencies) {
      const module = load(dependency)
      const candidates = new Map(module.labels)
      for (const entry of module.enums) for (const targetId of entry.ids) {
        const key = targetId.slice(entry.group.length + 1)
        const label = entry.group === 'agent.guide-menu' ? workflows.find((workflow) => workflow.workflowId === key)?.title : instanceLabels.get(key) || instanceLabels.get(key.split('.').at(-1))
        if (label) candidates.set(targetId, { label, tag: entry.tag })
      }
      for (const [targetId, { label, tag }] of candidates) {
      if (!targetIds.has(targetId) || guide.steps.some((step) => step.targetId === targetId)) continue
      const key = `${screen.id}:${targetId}`
      const stepId = 'help.' + createHash('sha256').update(key).digest('hex').slice(0, 32)
      const kind = ['input', 'textarea', 'select', 'SearchSelect'].includes(tag) ? '입력 항목' : ['button', 'Button', 'a', 'Link', 'ContextLink', 'summary'].includes(tag) ? '버튼·메뉴' : '영역'
      locations.set(key, { stepId, screenId: screen.id, targetId, title: `${screen.title} · ${label}`,
        instruction: `${screen.title} 화면의 ‘${label}’ ${kind} 위치입니다. 화면에 표시된 항목과 원천 근거를 확인하세요. 필요한 탭·상세·확인 창은 직접 열어주세요. 버튼 실행은 직접 진행하며, 비활성 항목은 현재 권한과 업무 상태를 확인하세요. 위치 안내는 실제 저장·계산·처리 완료를 뜻하지 않습니다.`,
        completionRuleId: 'USER_REVIEWED', next: [] })
    }
    }
  }
  generated.push({ workflowId: guide.workflowId, steps: [...locations.values()].sort((a, b) => a.stepId.localeCompare(b.stepId, 'en')) })
}
const file = path.join(folder, 'workflow-help.generated.json')
const content = JSON.stringify(generated, null, 2) + '\n'
if (process.argv.includes('--write')) fs.writeFileSync(file, content)
else if (!fs.existsSync(file) || fs.readFileSync(file, 'utf8') !== content) throw new Error('UI knowledge is stale. Run npm run generate:guide-knowledge.')
console.log(`Source UI knowledge: ${generated.reduce((total, guide) => total + guide.steps.length, 0)} registered locations; ${generated.length} workflows.`)
