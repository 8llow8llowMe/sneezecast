import { spawnSync } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { afterEach, describe, expect, it } from 'vitest'

import {
  benchmarkIndex,
  budgetWarnings,
  buildReport,
  median,
  readRun,
  redirectWarnings,
  summarize,
  toMarkdown,
} from './lighthouse-summary.mjs'

/** Lighthouse 결과(LHR)에서 요약이 읽는 부분만 흉내 낸다 */
function lhr(
  url,
  { perf = 0.9, lcp = 3000, script = 200 * 1024, runtimeError, finalUrl = url, bench = 2800 } = {},
) {
  const audit = (numericValue) => ({ numericValue })
  return {
    requestedUrl: url,
    finalDisplayedUrl: finalUrl,
    environment: { benchmarkIndex: bench },
    ...(runtimeError ? { runtimeError } : {}),
    categories: {
      performance: { score: runtimeError ? null : perf },
      accessibility: { score: 1 },
      'best-practices': { score: 1 },
      seo: { score: 1 },
    },
    audits: {
      'first-contentful-paint': audit(1500),
      'largest-contentful-paint': audit(lcp),
      'total-blocking-time': audit(20),
      'cumulative-layout-shift': audit(0.0123),
      'server-response-time': audit(9),
      'resource-summary': {
        details: {
          items: [
            { resourceType: 'total', transferSize: 500 * 1024 },
            { resourceType: 'script', transferSize: script },
          ],
        },
      },
    },
  }
}

describe('median', () => {
  it('홀수 개면 가운데, 짝수 개면 가운데 둘의 평균이다', () => {
    expect(median([3, 1, 2])).toBe(2)
    expect(median([4, 1, 3, 2])).toBe(2.5)
  })

  it('수가 아닌 값은 빼고, 남는 값이 없으면 null 이다', () => {
    expect(median([null, 5, undefined, Number.NaN])).toBe(5)
    expect(median([null, undefined])).toBeNull()
  })
})

describe('readRun', () => {
  it('점수는 0~100 정수로, 지표는 숫자 그대로 읽는다', () => {
    const run = readRun(lhr('http://localhost:3100/map', { perf: 0.913 }))
    expect(run.scores.performance).toBe(91)
    expect(run.metrics.lcp).toBe(3000)
    expect(run.metrics.scriptBytes).toBe(200 * 1024)
    expect(run.failed).toBe(false)
  })

  it('실행 오류가 있으면 실패로 보고 점수 · 지표를 비운다', () => {
    const run = readRun(lhr('http://localhost:3100/map', { runtimeError: { code: 'NO_FCP' } }))
    expect(run.failed).toBe(true)
    expect(run.scores.performance).toBeNull()
    expect(run.metrics.lcp).toBeNull()
  })

  it('감사가 빠진 결과도 던지지 않고 null 로 둔다', () => {
    const run = readRun({ requestedUrl: 'http://localhost:3100/', categories: {}, audits: {} })
    expect(run.scores.accessibility).toBeNull()
    expect(run.metrics.scriptBytes).toBeNull()
  })
})

describe('summarize', () => {
  it('URL 마다 처음 나온 순서로 묶어 값마다 중앙값을 낸다', () => {
    const rows = summarize([
      lhr('http://localhost:3100/start', { perf: 0.98 }),
      lhr('http://localhost:3100/?mock-auth=member', { lcp: 3800, perf: 0.88 }),
      lhr('http://localhost:3100/start', { perf: 0.96 }),
      lhr('http://localhost:3100/?mock-auth=member', { lcp: 3600, perf: 0.9 }),
      lhr('http://localhost:3100/start', { perf: 0.97 }),
      lhr('http://localhost:3100/?mock-auth=member', { lcp: 4000, perf: 0.86 }),
    ])
    expect(rows.map((row) => row.path)).toEqual(['/start', '/?mock-auth=member'])
    expect(rows[0]).toMatchObject({ runs: 3, failed: 0, scores: { performance: 97 } })
    expect(rows[1]).toMatchObject({ runs: 3, scores: { performance: 88 }, metrics: { lcp: 3800 } })
  })

  it('실패한 실행은 세되 중앙값에서 뺀다', () => {
    const [row] = summarize([
      lhr('http://localhost:3100/', { lcp: 3000 }),
      lhr('http://localhost:3100/', { runtimeError: { code: 'NO_FCP' } }),
    ])
    expect(row).toMatchObject({ runs: 2, failed: 1, metrics: { lcp: 3000 } })
  })
})

describe('toMarkdown', () => {
  it('표 머리와 화면마다 한 줄을 낸다', () => {
    const table = toMarkdown(summarize([lhr('http://localhost:3100/login', { lcp: 3243 })]))
    const lines = table.split('\n')
    expect(lines[0]).toContain('| 화면 |')
    expect(lines[2]).toBe(
      '| `/login` | 90 | 100 | 100 | 100 | 1.50 s | 3.24 s | 20 ms | 0.012 | 9 ms | 200 KiB | 1 |',
    )
  })

  it('값이 없으면 - 로, 실패가 있으면 실행 수 옆에 적는다', () => {
    const table = toMarkdown(
      summarize([lhr('http://localhost:3100/', { runtimeError: { code: 'NO_FCP' } })]),
    )
    expect(table.split('\n')[2]).toBe(
      '| `/` | - | - | - | - | - | - | - | - | - | - | 1 (실패 1) |',
    )
  })
})

describe('budgetWarnings', () => {
  it('예산을 넘은 항목(passed=false)만 화면 · 항목 · 기대값 · 실제값으로 적는다', () => {
    const lines = budgetWarnings([
      {
        name: 'minScore',
        expected: 0.85,
        actual: 0.8,
        operator: '>=',
        passed: false,
        auditId: 'categories',
        auditProperty: 'performance',
        level: 'warn',
        url: 'http://localhost:3100/',
      },
      {
        name: 'maxNumericValue',
        expected: 4000,
        actual: 4210.4,
        operator: '<=',
        passed: false,
        auditId: 'largest-contentful-paint',
        level: 'warn',
        url: 'http://localhost:3100/map',
      },
      {
        name: 'maxNumericValue',
        expected: 150,
        actual: 20,
        operator: '<=',
        passed: true,
        auditId: 'total-blocking-time',
        level: 'warn',
        url: 'http://localhost:3100/map',
      },
    ])
    expect(lines).toEqual([
      '- `/` categories.performance: 0.8 (예산 >= 0.85)',
      '- `/map` largest-contentful-paint: 4210 (예산 <= 4000)',
    ])
  })

  it('결과가 배열이 아니면 빈 목록이다', () => {
    expect(budgetWarnings(undefined)).toEqual([])
    expect(budgetWarnings({})).toEqual([])
  })
})

describe('리다이렉트', () => {
  it('마지막 주소의 경로가 요청한 경로와 다르면 리다이렉트다(쿼리 차이는 보지 않는다)', () => {
    expect(
      readRun(lhr('http://localhost:3100/', { finalUrl: 'http://localhost:3100/start' }))
        .redirected,
    ).toBe(true)
    expect(
      readRun(lhr('http://localhost:3100/map', { finalUrl: 'http://localhost:3100/map?x=1' }))
        .redirected,
    ).toBe(false)
  })

  it('finalDisplayedUrl 이 없으면 mainDocumentUrl 로 보고, 둘 다 없으면 리다이렉트로 보지 않는다', () => {
    const base = lhr('http://localhost:3100/')
    delete base.finalDisplayedUrl
    expect(readRun({ ...base, mainDocumentUrl: 'http://localhost:3100/start' }).redirected).toBe(
      true,
    )
    expect(readRun(base).redirected).toBe(false)
  })

  it('한 번이라도 리다이렉트된 화면은 표에 도착 경로를 적고 경고에 올린다', () => {
    const rows = summarize([
      lhr('http://localhost:3100/'),
      lhr('http://localhost:3100/', { finalUrl: 'http://localhost:3100/start' }),
      lhr('http://localhost:3100/map'),
    ])
    expect(rows.map((row) => row.redirectTo)).toEqual(['/start', null])
    expect(toMarkdown(rows).split('\n')[2]).toMatch(/^\| `\/` → `\/start` \|/)
    expect(redirectWarnings(rows)).toEqual([
      '- `/` 리다이렉트: `/start` 를 쟀다(요청한 화면이 아님)',
    ])
  })
})

describe('benchmarkIndex', () => {
  it('측정 기기 성능 지표의 중앙값을 낸다', () => {
    expect(
      benchmarkIndex([
        lhr('http://localhost:3100/', { bench: 1000 }),
        lhr('http://localhost:3100/', { bench: 3000 }),
        lhr('http://localhost:3100/', { bench: 2000 }),
      ]),
    ).toBe(2000)
  })
})

describe('buildReport', () => {
  it('리다이렉트와 예산 초과를 합쳐 경고 수를 센다', () => {
    const { text, warningCount } = buildReport({
      lhrs: [lhr('http://localhost:3100/', { finalUrl: 'http://localhost:3100/start' })],
      assertions: [
        {
          name: 'maxNumericValue',
          expected: 4000,
          actual: 4500,
          operator: '<=',
          passed: false,
          auditId: 'largest-contentful-paint',
          url: 'http://localhost:3100/',
        },
      ],
    })
    expect(warningCount).toBe(2)
    expect(text).toContain('측정 기기 성능 지표(benchmarkIndex) 중앙값: 2800')
    expect(text).toContain('경고 2건 (차단하지 않음)')
  })

  it('예산 확인 결과가 없으면 그 사실을 적는다', () => {
    const { text, warningCount } = buildReport({
      lhrs: [lhr('http://localhost:3100/')],
      assertions: null,
    })
    expect(warningCount).toBe(0)
    expect(text).toContain('예산 확인 결과 없음')
  })
})

describe('스크립트 실행', () => {
  const script = fileURLToPath(new URL('./lighthouse-summary.mjs', import.meta.url))
  /** @type {string[]} */
  const dirs = []
  const tempDir = () => {
    const dir = mkdtempSync(join(tmpdir(), 'lhci-summary-'))
    dirs.push(dir)
    return dir
  }
  const run = (args, env = {}) =>
    spawnSync(process.execPath, [script, ...args], {
      encoding: 'utf8',
      env: { PATH: process.env.PATH, ...env },
    })

  afterEach(() => {
    for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true })
  })

  it('결과가 없으면 종료 코드 1 이다', () => {
    const result = run([tempDir()])
    expect(result.status).toBe(1)
    expect(result.stderr).toContain('Lighthouse 결과가 없습니다')
  })

  it('깨진 파일은 건너뛰고 알리며, 나머지로 표와 경고를 낸다', () => {
    const dir = tempDir()
    writeFileSync(join(dir, 'lhr-1.json'), JSON.stringify(lhr('http://localhost:3100/login')))
    writeFileSync(join(dir, 'lhr-2.json'), '{ 깨진')
    writeFileSync(
      join(dir, 'assertion-results.json'),
      JSON.stringify([
        {
          name: 'minScore',
          expected: 0.95,
          actual: 0.9,
          operator: '>=',
          passed: false,
          auditId: 'categories',
          auditProperty: 'performance',
          url: 'http://localhost:3100/login',
        },
      ]),
    )
    const result = run([dir])
    expect(result.status).toBe(0)
    expect(result.stderr).toContain('건너뜀')
    expect(result.stderr).toContain('lhr-2.json')
    expect(result.stdout).toContain('| `/login` | 90 |')
    expect(result.stdout).toContain('- `/login` categories.performance: 0.9 (예산 >= 0.95)')
  })

  it('--github 는 요약 파일에 덧붙이고 경고가 있으면 ::warning 을 찍는다', () => {
    const dir = tempDir()
    const summary = join(dir, 'summary.md')
    writeFileSync(summary, '')
    writeFileSync(
      join(dir, 'lhr-1.json'),
      JSON.stringify(lhr('http://localhost:3100/', { finalUrl: 'http://localhost:3100/start' })),
    )
    writeFileSync(join(dir, 'assertion-results.json'), '[]')
    const result = run([dir, '--github'], { GITHUB_STEP_SUMMARY: summary })
    expect(result.status).toBe(0)
    expect(result.stdout.trim()).toBe('::warning title=Lighthouse::예산 경고 1건 (차단하지 않음)')
    expect(readFileSync(summary, 'utf8')).toContain('`/` → `/start`')
  })

  it('--github 에서 결과가 없으면 ::warning 을 찍고 종료 코드 1 이다', () => {
    const result = run([tempDir(), '--github'], { GITHUB_STEP_SUMMARY: '/dev/null' })
    expect(result.status).toBe(1)
    expect(result.stdout).toContain('::warning title=Lighthouse::결과 없음')
  })
})
