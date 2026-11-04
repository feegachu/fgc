import { readdirSync, readFileSync } from 'node:fs'
import { expect, it } from 'vitest'

const RAW_HTML =
  /dangerouslySetInnerHTML|(inner|outer)HTML['"\]]*\s*\+?=|insertAdjacentHTML|createContextualFragment|parseFromString|setHTMLUnsafe|document\.write|\beval\(|new Function\(/

// SER-004, SRC-028: 화면 출력 이스케이프는 React 텍스트 노드에 맡긴다. 이 경로가 생기면 CSP 한 겹만 남으므로 리뷰에서 다시 판단한다.
it('화면 코드에 문자열을 HTML 로 해석하거나 실행하는 경로가 없다', () => {
  for (const sample of ['el.innerHTML = x', 'el.innerHTML += x', "el['outerHTML'] = x", 'range.createContextualFragment(x)', 'eval(x)']) {
    expect(RAW_HTML.test(sample)).toBe(true)
  }
  const sources = readdirSync('src', { recursive: true, encoding: 'utf8' }).filter(
    (name) => /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name),
  )
  expect(sources.length).toBeGreaterThan(100)
  expect(sources.filter((name) => RAW_HTML.test(readFileSync(`src/${name}`, 'utf8')))).toEqual([])
})
