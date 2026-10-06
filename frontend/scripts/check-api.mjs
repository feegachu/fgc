import { readFile } from 'node:fs/promises'
import openapiTS, { astToString, COMMENT_HEADER } from 'openapi-typescript'

const generated = COMMENT_HEADER + astToString(await openapiTS(new URL('../openapi/schema.json', import.meta.url)))
const checkedIn = await readFile(new URL('../src/lib/api/schema.d.ts', import.meta.url), 'utf8')
// Git의 Windows 체크아웃(CRLF)과 생성기의 LF 차이는 API 타입 변경이 아니다.
const normalizeLineEndings = (text) => text.replace(/\r\n/g, '\n')
if (normalizeLineEndings(generated) !== normalizeLineEndings(checkedIn)) {
  console.error('OpenAPI 타입이 스펙과 다릅니다. npm run generate:api를 실행하세요.')
  process.exitCode = 1
}
