import { readFileSync, readdirSync } from 'node:fs'
import { resolve } from 'node:path'
import ts from 'typescript'
import { expect, it } from 'vitest'
import { workflowWalkthrough } from './workflow-walkthrough'
import { paymentWalkthrough } from './payment-walkthrough'
import { initialForm, newRow } from '../../transaction/form-model'
import { STEPS } from './state'

// Check the actual catalogs against JSX declarations, scoped to their owning screen.
// Runtime tests cover conditional visibility and duplicate mounted instances.
function sourceFiles(domain: string) {
  const dir = resolve(import.meta.dirname, '../..', domain)
  return readdirSync(dir).filter(name => name.endsWith('.tsx') && !name.includes('.test.')).map(name => resolve(dir, name))
}
function countBinding(sources: string[], selector: string) {
  const attribute = selector.match(/\[([\w-]+)="([^"]+)"\]/)
  const id = selector.match(/^#([\w-]+)$/)
  const name = id ? 'id' : attribute?.[1]
  const value = id?.[1] ?? attribute?.[2]
  const tail = attribute ? selector.slice(selector.indexOf(attribute[0]) + attribute[0].length).trim() : ''
  const literalMatches = (node: ts.Node | undefined): boolean => !!node && (
    ts.isStringLiteral(node) ? node.text === value :
    ts.isJsxExpression(node) ? literalMatches(node.expression) :
    ts.isConditionalExpression(node) ? literalMatches(node.whenTrue) || literalMatches(node.whenFalse) : false
  )
  let count = 0
  for (const file of sources) {
    const source = readFileSync(file, 'utf8')
    const ast = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX)
    const visit = (node: ts.Node) => {
      if (ts.isJsxAttribute(node) && node.name.getText(ast) === name && literalMatches(node.initializer)) {
        if (!tail) count++
        else {
          const tag = tail.match(/^\w+/)?.[0]
          const type = tail.match(/type="([^"]+)"/)?.[1]
          const descendants: ts.Node[] = []
          const expanded = new Set<string>()
          const collect = (child: ts.Node) => {
            if (ts.isJsxOpeningElement(child) || ts.isJsxSelfClosingElement(child)) {
              const component = child.tagName.getText(ast)
              if (!expanded.has(component) && /^[A-Z]/.test(component)) {
                expanded.add(component)
                const definition = ast.statements.find(statement => ts.isFunctionDeclaration(statement) && statement.name?.text === component)
                if (definition) collect(definition)
              }
            }
            if ((ts.isJsxOpeningElement(child) || ts.isJsxSelfClosingElement(child)) && child.tagName.getText(ast) === tag && (!type || child.attributes.properties.some(prop => ts.isJsxAttribute(prop) && prop.name.getText(ast) === 'type' && prop.initializer && ts.isStringLiteral(prop.initializer) && prop.initializer.text === type))) descendants.push(child)
            ts.forEachChild(child, collect)
          }
          collect(node.parent.parent.parent)
          count += descendants.length
        }
      }
      // Contract amountInput emits name={field}; each literal call is a binding.
      if (name === 'name' && ts.isCallExpression(node) && node.expression.getText(ast) === 'amountInput' && node.arguments[0] && ts.isStringLiteral(node.arguments[0]) && node.arguments[0].text === value) {
        expect(source).toContain('name={field}')
        count++
      }
      // Attribution control emits an ID for each row and literal field.
      const dynamic = value?.match(/^attr-\d+-(.+)$/)
      if (name === 'id' && dynamic && ts.isJsxSpreadAttribute(node) && ts.isCallExpression(node.expression) && node.expression.expression.getText(ast) === 'control') {
        const field = node.expression.arguments[1]
        if (field && ts.isStringLiteral(field) && field.text === dynamic[1]) {
          expect(source).toContain('id: `attr-${row.key}-${field}`')
          count++
        }
      }
      if (name === 'data-guide-condition' && ts.isJsxAttribute(node) && node.name.getText(ast) === name && node.initializer?.getText(ast) === '{condition.no}') count++
      if (selector === 'h1' && ts.isJsxOpeningElement(node) && node.tagName.getText(ast) === 'h1') count++
      ts.forEachChild(node, visit)
    }
    visit(ast)
  }
  return count
}
const scenarios = [
  ['/contracts/new', 'contract'], ['/contracts/1', 'contract'], ['/schedules/1', 'schedule'],
  ['/transactions/new', 'transaction'], ['/transactions', 'transaction'],
  ['/validation-runs', 'validation'], ['/validation-runs/1', 'validation'],
  ['/reconciliation', 'reconciliation'], ['/exceptions', 'exception'],
] as const
const routes: Record<string, string[]> = {
  'contract.create': ['/contracts/new', '/contracts/1'], 'schedule.review': ['/contracts/1', '/schedules/1'],
  'payment.precheck': ['/transactions/new', '/transactions'], 'payment.confirm': ['/transactions/new', '/transactions'],
  'validation.execute': ['/validation-runs', '/validation-runs/1'], 'reconciliation.review': ['/reconciliation'],
  'exception.resolve': ['/exceptions'], 'validation.finalize': ['/validation-runs/1'],
}
for (const stage of STEPS.filter(stage => stage !== 'payment.draft')) {
  it(`binds every ${stage} explanation to its screen source`, () => {
    for (const path of routes[stage]) for (const selected of [false, true]) for (const corrected of [false, true]) {
      const domain = scenarios.find(([route]) => route === path)![1]
      for (const step of workflowWalkthrough(stage, path, selected, corrected)) {
        expect(countBinding(step.selector === 'h1' && domain === 'contract' ? sourceFiles(domain).filter(file => file.endsWith('ContractDetailPage.tsx')) : sourceFiles(domain), step.selector), `${path} ${step.selector}`).toBe(1)
      }
    }
  })
}
it('binds insurer/agent and conditional multi-row payment selectors', () => {
  for (const paymentStage of ['GA_TO_FC', 'INSURER_TO_GA'] as const) {
    const form = { ...initialForm('2026-07'), paymentStage }
    const rows = [newRow(1, ''), { ...newRow(2, ''), decision: 'EXCLUDED' }]
    const steps = paymentWalkthrough({ role: 'SETTLEMENT', canProcess: true, paymentId: null, ready: true, valid: false, dirty: true, form, rows })
    for (const step of steps) expect(countBinding(sourceFiles('transaction'), step.selector), step.selector).toBe(1)
  }
})
it('rejects renamed and duplicate declarations', () => {
  expect(countBinding(sourceFiles('contract'), '[name="missing-contract-field"]')).toBe(0)
  const file = resolve(import.meta.dirname, '../../contract/ContractFormPage.tsx')
  expect(countBinding([file, file], '[name="contractDate"]')).toBe(2)
})
