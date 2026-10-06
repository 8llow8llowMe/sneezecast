import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, normalize, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

import { describe, expect, it } from 'vitest'

/**
 * `'use client'` 가 없는 모듈(서버 페이지에서도 쓸 수 있는 모듈)이 `'use client'` 모듈에서 **컴포넌트가 아닌 값**을
 * 가져오지 않는지 본다. 서버에서는 `'use client'` 모듈의 값이 실제 값이 아니라 클라이언트 참조가 된다 — 문자열 상수를
 * 가져오면 `searchParams.get(상수)` 가 늘 null 이 된다(#206). vitest 에는 이 경계가 없어 동작 테스트로는 잡히지 않는다.
 *
 * 컴포넌트(대문자로 시작하고 소문자가 이어지는 이름)는 서버에서 클라이언트 참조로 그리는 것이 정상이라 뺀다.
 * 타입만 가져오는 것(`import type` · `type X`)도 뺀다.
 */

const FRONTEND = join(dirname(fileURLToPath(import.meta.url)), '..')

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return name === 'node_modules' ? [] : sourceFiles(path)
    return /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name) ? [path] : []
  })
}

function isClientModule(path: string): boolean {
  return /^\s*['"]use client['"]/.test(readFileSync(path, 'utf8'))
}

function resolveImport(from: string, specifier: string): string | null {
  let base: string
  if (specifier.startsWith('@/')) base = join(FRONTEND, 'src', specifier.slice(2))
  else if (specifier.startsWith('.')) base = normalize(join(dirname(from), specifier))
  else return null
  const found = ['.ts', '.tsx', '/index.ts', '/index.tsx'].map((ext) => base + ext).find(existsSync)
  return found ?? null
}

const NAMED_IMPORT = /import\s+(type\s+)?\{([^}]*)\}\s+from\s+['"]([^'"]+)['"]/g
const COMPONENT_NAME = /^[A-Z][a-z]/

function valueImportsFromClientModules(file: string): string[] {
  const source = readFileSync(file, 'utf8')
  const found: string[] = []
  for (const [, typeOnly, names, specifier] of source.matchAll(NAMED_IMPORT)) {
    if (typeOnly || !names || !specifier) continue
    const target = resolveImport(file, specifier)
    if (!target || !isClientModule(target)) continue
    const values = names
      .split(',')
      .map((name) => name.trim())
      .filter((name) => name && !name.startsWith('type '))
      .map((name) => name.split(/\s+as\s+/)[0] ?? name)
      .filter((name) => !COMPONENT_NAME.test(name))
    for (const name of values) found.push(`${relative(FRONTEND, file)} ← ${specifier}: ${name}`)
  }
  return found
}

describe("'use client' 경계", () => {
  it("'use client' 가 없는 모듈은 'use client' 모듈에서 컴포넌트가 아닌 값을 가져오지 않는다", () => {
    const files = [...sourceFiles(join(FRONTEND, 'src')), ...sourceFiles(join(FRONTEND, 'app'))]
    const violations = files
      .filter((file) => !isClientModule(file))
      .flatMap(valueImportsFromClientModules)
    expect(violations).toEqual([])
  })
})
