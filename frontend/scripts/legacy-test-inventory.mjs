import { readFileSync, readdirSync, mkdirSync, writeFileSync } from 'node:fs'

// Reproducible assertion inventory; this does NOT infer semantic equivalence or approve deletion.
const javaDirectory = '../src/test/java/com/susukkang/fgc/ui'
const classes = [
  'PublishingTemplateStructureTest',
  'AppShellWorkspaceStructureTest',
  'ArbitragePublishingStructureTest',
  'AuditTemplateStructureTest',
  'CapPublishingTemplateStructureTest',
  'GlobalMonthSelectorStructureTest',
  'PendingActionButtonStructureTest',
  'SidebarSectionStateStructureTest',
  'StatusBadgeDesignTokenTest',
]
function closing(text, start, open = '(', close = ')') {
  let depth = 0,
    quote = ''
  for (let i = start; i < text.length; i++) {
    const char = text[i]
    if (quote) {
      if (char === '\\') i++
      else if (char === quote) quote = ''
      continue
    }
    if (char === '"' || char === "'" || char === '`') {
      quote = char
      continue
    }
    if (char === open) depth++
    if (char === close) {
      depth--
      if (depth === 0) return i + 1
    }
  }
  throw new Error(`Unbalanced ${open} at ${start}`)
}
const entries = []
for (const name of classes) {
  const file = `${javaDirectory}/${name}.java`
  const source = readFileSync(file, 'utf8').replace(/\r\n/g, '\n')
  const methods = [...source.matchAll(/^    void (\w+)\([^\n]*\{$/gm)]
  for (let index = 0; index < methods.length; index++) {
    const method = methods[index]
    const end = methods[index + 1]?.index ?? source.indexOf('\n    private ', method.index)
    const body = source.slice(method.index, end < 0 ? undefined : end)
    const assertions = [
      ...body.matchAll(/\.(?:contains\w*|doesNotContain\w*|isEqualTo|isTrue|isFalse|hasSize)\(/g),
    ].map((match) => {
      const start = match.index + match[0].length - 1
      return {
        line: source.slice(0, method.index + match.index).split('\n').length,
        expression: body.slice(match.index + 1, closing(body, start)),
      }
    })
    entries.push({ source: file.slice(3), test: `${name}#${method[1]}`, assertions })
  }
}
for (const name of readdirSync('../src/test/js').filter((name) => name.endsWith('.test.cjs'))) {
  const file = `../src/test/js/${name}`
  const source = readFileSync(file, 'utf8').replace(/\r\n/g, '\n')
  const methods = [...source.matchAll(/^test\("([^"]+)"/gm)]
  for (let index = 0; index < methods.length; index++) {
    const method = methods[index]
    const body = source.slice(method.index, methods[index + 1]?.index)
    const assertions = [...body.matchAll(/assert\.\w+\(/g)].map((match) => ({
      line: source.slice(0, method.index + match.index).split('\n').length,
      expression: body.slice(match.index, body.indexOf(';', match.index) + 1),
    }))
    entries.push({ source: file.slice(3), test: `${name}#${method[1]}`, assertions })
  }
}
if (entries.length !== 65)
  throw new Error(`Expected 48 Java methods + 17 node tests, found ${entries.length}; review the inventory`)
mkdirSync('docs/418', { recursive: true })
const mappingRows = readFileSync('docs/418/coverage-map.md', 'utf8')
  .split('\n')
  .filter((line) => line.startsWith('| '))
  .map((line) =>
    line
      .split('|')
      .slice(1, -1)
      .map((cell) => cell.trim()),
  )
const escape = (text) => text.replace(/\|/g, '&#124;').replace(/\r?\n/g, ' ').replace(/`/g, '&#96;')
const output = [
  '# 1차 검사 항목 전수 목록',
  '',
  '생성: `node scripts/legacy-test-inventory.mjs`. 9개 Java 클래스의 48개 메서드 + node 4개 파일의 17개 테스트.',
  '이 목록은 문자열/정규식/값 검사를 개별 행으로 나열한다. 각 메서드의 대체 테스트·제외 사유·잔여 차이는 [대응표](coverage-map.md)를 함께 읽는다.',
  '반복문에서 검사하는 템플릿 집합과 helper의 맥락은 연결된 원본을 따른다. 항목 수는 실행된 JUnit 테스트 수가 아니다.',
  '',
]
for (const [index, entry] of entries.entries()) {
  const [suite, name] = entry.test.split('#')
  const key =
    suite === 'PublishingTemplateStructureTest'
      ? name
      : suite.endsWith('.test.cjs')
        ? `${suite.replace('.test.cjs', '')} / ${name}`
        : entry.test
  const candidates = mappingRows.filter(
    (row) => row[0] === key || (suite === 'format.test.cjs' && key.startsWith(row[0])),
  )
  const mapping = candidates.length === 1 ? candidates[0] : undefined
  if (!mapping) throw new Error(`Missing replacement/exclusion mapping: ${key}`)
  output.push(
    `## ${entry.test}`,
    '',
    `<a id="mapping-${index + 1}"></a>`,
    '',
    `대체 테스트: ${mapping[1]}.`,
    '',
    `구조 제외·남은 확인: ${mapping[2]}.`,
    '',
    '| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |',
    '|---|---|---|',
  )
  for (const assertion of entry.assertions)
    output.push(
      `| [L${assertion.line}](../../../${entry.source}#L${assertion.line}) | \`${escape(assertion.expression)}\` | [이 메서드의 대응·제외](#mapping-${index + 1}) |`,
    )
  output.push('')
}
writeFileSync('docs/418/assertion-inventory.md', output.join('\n'))
console.log(
  `${entries.length} tests / ${entries.reduce((sum, entry) => sum + entry.assertions.length, 0)} assertions inventoried`,
)
