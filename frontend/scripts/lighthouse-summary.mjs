// @ts-check
/**
 * Lighthouse CI 결과(`.lighthouseci/lhr-*.json`)를 화면별 중앙값 표(Markdown)로 낸다 (#177).
 *
 * `pnpm perf:lighthouse` 가 수집 뒤 부르고, frontend-ci 는 `--github` 로 불러 실행 요약(Step Summary)에 붙인다.
 * 측정 방법 · 예산 · 기준선은 docs/performance.md 가 정본이다.
 *
 * 실행: node scripts/lighthouse-summary.mjs [결과 폴더=.lighthouseci] [--github]
 * - 표 아래에 측정 기기 성능 지표(benchmarkIndex) 중앙값과 경고(예산 초과 · 리다이렉트)를 적는다.
 * - `--github`: 표를 `$GITHUB_STEP_SUMMARY` 파일에 덧붙이고, 경고가 있으면 `::warning` 을 찍어 체크 화면에 보인다(차단하지 않음).
 * - 읽을 수 있는 결과 파일이 하나도 없으면 종료 코드 1 이다(수집이 돌지 않은 것을 "통과" 로 보이지 않게). 깨진 파일은 건너뛰고 알린다.
 */

import { appendFileSync, readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'

const CATEGORIES = /** @type {const} */ (['performance', 'accessibility', 'best-practices', 'seo'])

/** 표의 지표 열 → Lighthouse 감사 id */
const METRIC_AUDITS = /** @type {const} */ ({
  fcp: 'first-contentful-paint',
  lcp: 'largest-contentful-paint',
  tbt: 'total-blocking-time',
  cls: 'cumulative-layout-shift',
  // 문서 요청의 서버 응답 시간(관측값, 쓰로틀링 흉내 없음)
  ttfb: 'server-response-time',
})

const HEADING = '## Lighthouse (모바일 기본 설정 · URL 마다 중앙값 · 경고만)'

/**
 * @typedef {'performance' | 'accessibility' | 'best-practices' | 'seo'} Category
 * @typedef {'fcp' | 'lcp' | 'tbt' | 'cls' | 'ttfb' | 'scriptBytes'} Metric
 * @typedef {{ url: string, finalUrl: string | null, redirected: boolean, failed: boolean, benchmarkIndex: number | null, scores: Record<Category, number | null>, metrics: Record<Metric, number | null> }} Run
 * @typedef {{ path: string, redirectTo: string | null, runs: number, failed: number, scores: Record<Category, number | null>, metrics: Record<Metric, number | null> }} Row
 */

/**
 * 수인 값만 골라 중앙값을 낸다. 짝수 개면 가운데 둘의 평균, 남는 값이 없으면 null.
 * @param {ReadonlyArray<unknown>} values
 * @returns {number | null}
 */
export function median(values) {
  const nums = values
    .filter((v) => typeof v === 'number' && Number.isFinite(v))
    .map(Number)
    .sort((a, b) => a - b)
  if (nums.length === 0) return null
  const mid = Math.floor(nums.length / 2)
  return nums.length % 2 === 1 ? nums[mid] : (nums[mid - 1] + nums[mid]) / 2
}

/** @param {unknown} value */
function numberOrNull(value) {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

/** @param {string} url */
function displayPath(url) {
  try {
    const { pathname, search } = new URL(url)
    return pathname + search
  } catch {
    return url
  }
}

/** @param {string} url */
function pathnameOf(url) {
  try {
    return new URL(url).pathname
  } catch {
    return url
  }
}

/**
 * LHR 하나에서 표에 쓰는 값만 읽는다. 실행 오류(`runtimeError`)가 있으면 실패로 보고 값을 비운다.
 * 감사가 빠져 있어도 던지지 않고 null 로 둔다(Lighthouse 판이 바뀌어 id 가 사라진 경우).
 *
 * **리다이렉트**: 마지막에 보인 주소(`finalDisplayedUrl`, 없으면 `mainDocumentUrl`)의 경로가 요청한 경로와 다르면
 * 요청한 화면이 아니라 다른 화면을 잰 것이다(예: 쿠키가 빠져 `/` 가 `/start` 로 307). 쿼리 차이는 보지 않는다.
 * @param {any} lhr
 * @returns {Run}
 */
export function readRun(lhr) {
  const failed = Boolean(lhr?.runtimeError)
  const audits = lhr?.audits ?? {}
  const url = String(lhr?.requestedUrl ?? '')
  const final = lhr?.finalDisplayedUrl ?? lhr?.mainDocumentUrl
  const finalUrl = typeof final === 'string' && final !== '' ? final : null
  const redirected = finalUrl !== null && pathnameOf(finalUrl) !== pathnameOf(url)

  /** @type {Record<Category, number | null>} */
  const scores = { performance: null, accessibility: null, 'best-practices': null, seo: null }
  /** @type {Record<Metric, number | null>} */
  const metrics = { fcp: null, lcp: null, tbt: null, cls: null, ttfb: null, scriptBytes: null }
  if (!failed) {
    for (const id of CATEGORIES) {
      const score = numberOrNull(lhr?.categories?.[id]?.score)
      scores[id] = score === null ? null : Math.round(score * 100)
    }
    for (const [key, auditId] of Object.entries(METRIC_AUDITS)) {
      metrics[/** @type {Metric} */ (key)] = numberOrNull(audits[auditId]?.numericValue)
    }
    // 로드 중 받은 스크립트 전송량(압축 뒤). 로드 직후 Link 프리페치로 받은 청크도 들어간다
    const items = audits['resource-summary']?.details?.items ?? []
    const script = items.find((/** @type {any} */ item) => item?.resourceType === 'script')
    metrics.scriptBytes = numberOrNull(script?.transferSize)
  }
  return {
    url,
    finalUrl,
    redirected,
    failed,
    benchmarkIndex: numberOrNull(lhr?.environment?.benchmarkIndex),
    scores,
    metrics,
  }
}

/**
 * 실행들을 URL 로 묶어(처음 나온 순서) 값마다 중앙값을 낸다. 실패한 실행은 세되 중앙값에서 뺀다.
 * 한 번이라도 리다이렉트됐으면 그 도착 경로를 `redirectTo` 에 둔다.
 * @param {ReadonlyArray<any>} lhrs
 * @returns {Row[]}
 */
export function summarize(lhrs) {
  /** @type {Map<string, Run[]>} */
  const groups = new Map()
  for (const lhr of lhrs) {
    const run = readRun(lhr)
    const runs = groups.get(run.url) ?? []
    runs.push(run)
    groups.set(run.url, runs)
  }

  return [...groups].map(([url, runs]) => {
    const ok = runs.filter((run) => !run.failed)
    /** @type {Record<Category, number | null>} */
    const scores = { performance: null, accessibility: null, 'best-practices': null, seo: null }
    for (const id of CATEGORIES) scores[id] = median(ok.map((run) => run.scores[id]))
    /** @type {Record<Metric, number | null>} */
    const metrics = { fcp: null, lcp: null, tbt: null, cls: null, ttfb: null, scriptBytes: null }
    for (const key of /** @type {Metric[]} */ (Object.keys(metrics))) {
      metrics[key] = median(ok.map((run) => run.metrics[key]))
    }
    const redirectedRun = runs.find((run) => run.redirected)
    return {
      path: displayPath(url),
      redirectTo: redirectedRun?.finalUrl ? displayPath(redirectedRun.finalUrl) : null,
      runs: runs.length,
      failed: runs.length - ok.length,
      scores,
      metrics,
    }
  })
}

/**
 * 측정 기기의 CPU 성능 지표(Lighthouse `benchmarkIndex`) 중앙값. 기기마다 시뮬레이션 결과가 달라 로컬 · CI 를 견줄 때 쓴다.
 * @param {ReadonlyArray<any>} lhrs
 */
export function benchmarkIndex(lhrs) {
  return median(lhrs.map((lhr) => readRun(lhr).benchmarkIndex))
}

/**
 * @param {number | null} value
 * @param {(v: number) => string} format
 */
function cell(value, format) {
  return value === null ? '-' : format(value)
}

/**
 * @param {ReadonlyArray<Row>} rows
 * @returns {string}
 */
export function toMarkdown(rows) {
  const head = [
    '| 화면 | 성능 | 접근성 | 모범 사례 | SEO | FCP | LCP | TBT | CLS | TTFB(서버) | JS 전송량 | 실행 수 |',
    '| ---- | ---: | -----: | --------: | --: | --: | --: | --: | --: | ---------: | --------: | ------: |',
  ]
  const seconds = (/** @type {number} */ ms) => `${(ms / 1000).toFixed(2)} s`
  const ms = (/** @type {number} */ v) => `${Math.round(v)} ms`
  const score = (/** @type {number} */ v) => String(Math.round(v))
  const body = rows.map((row) =>
    [
      row.redirectTo ? `\`${row.path}\` → \`${row.redirectTo}\`` : `\`${row.path}\``,
      ...CATEGORIES.map((id) => cell(row.scores[id], score)),
      cell(row.metrics.fcp, seconds),
      cell(row.metrics.lcp, seconds),
      cell(row.metrics.tbt, ms),
      cell(row.metrics.cls, (v) => v.toFixed(3)),
      cell(row.metrics.ttfb, ms),
      cell(row.metrics.scriptBytes, (v) => `${Math.round(v / 1024)} KiB`),
      row.failed > 0 ? `${row.runs} (실패 ${row.failed})` : String(row.runs),
    ].join(' | '),
  )
  return [...head, ...body.map((line) => `| ${line} |`)].join('\n')
}

/** @param {number} value */
function formatValue(value) {
  return Math.abs(value) < 10 ? String(Math.round(value * 1000) / 1000) : String(Math.round(value))
}

/**
 * `lhci assert` 가 남긴 `assertion-results.json` 에서 예산을 넘은 항목만 한 줄씩 적는다.
 * 모든 예산이 `warn` 이라 넘어도 수집은 실패하지 않는다 — 이 목록이 경고를 사람 눈에 띄게 하는 자리다.
 * @param {unknown} results
 * @returns {string[]}
 */
export function budgetWarnings(results) {
  if (!Array.isArray(results)) return []
  return results
    .filter((result) => result && result.passed === false)
    .map((result) => {
      const target = result.auditProperty
        ? `${result.auditId}.${result.auditProperty}`
        : result.auditId
      const actual = typeof result.actual === 'number' ? formatValue(result.actual) : '-'
      const expected = typeof result.expected === 'number' ? formatValue(result.expected) : '-'
      return `- \`${displayPath(String(result.url ?? ''))}\` ${target}: ${actual} (예산 ${result.operator} ${expected})`
    })
}

/**
 * 리다이렉트된 화면을 경고로 적는다. 예산 확인은 도착한 화면의 값으로 됐으므로 통과로 보여도 믿을 수 없다.
 * @param {ReadonlyArray<Row>} rows
 * @returns {string[]}
 */
export function redirectWarnings(rows) {
  return rows
    .filter((row) => row.redirectTo !== null)
    .map((row) => `- \`${row.path}\` 리다이렉트: \`${row.redirectTo}\` 를 쟀다(요청한 화면이 아님)`)
}

/**
 * 결과 폴더를 읽는다. LHR 은 파일마다 따로 읽어 깨진 파일은 건너뛰고 `skipped` 에 둔다.
 * `assertion-results.json` 이 없거나 깨졌으면 `assertions` 가 null 이다(`lhci assert` 가 돌지 않음).
 * @param {string} dir
 */
export function loadResults(dir) {
  let names = []
  try {
    // 파일 이름의 숫자가 수집 시각이라 이름순 = 수집 순서다
    names = readdirSync(dir)
      .filter((name) => /^lhr-.*\.json$/.test(name))
      .sort()
  } catch {
    // 폴더가 없으면 결과 없음
  }
  /** @type {any[]} */
  const lhrs = []
  /** @type {string[]} */
  const skipped = []
  for (const name of names) {
    try {
      lhrs.push(JSON.parse(readFileSync(join(dir, name), 'utf8')))
    } catch {
      skipped.push(name)
    }
  }
  /** @type {unknown} */
  let assertions = null
  try {
    assertions = JSON.parse(readFileSync(join(dir, 'assertion-results.json'), 'utf8'))
  } catch {
    assertions = null
  }
  return { lhrs, skipped, assertions }
}

/**
 * 요약 본문(머리 · 표 · 기기 지표 · 경고)과 경고 수.
 * @param {{ lhrs: ReadonlyArray<any>, assertions: unknown }} results
 */
export function buildReport({ lhrs, assertions }) {
  const rows = summarize(lhrs)
  const warnings = [...redirectWarnings(rows), ...budgetWarnings(assertions)]
  const bench = benchmarkIndex(lhrs)
  const lines = [
    HEADING,
    '',
    toMarkdown(rows),
    '',
    `측정 기기 성능 지표(benchmarkIndex) 중앙값: ${bench === null ? '-' : Math.round(bench)}`,
    '',
  ]
  if (warnings.length > 0) {
    lines.push(`경고 ${warnings.length}건 (차단하지 않음)`, '', ...warnings)
  } else {
    lines.push(
      assertions === null
        ? '경고 없음 (예산 확인 결과 없음 — lhci assert 가 돌지 않음)'
        : '경고 없음',
    )
  }
  return { text: `${lines.join('\n')}\n`, warningCount: warnings.length }
}

/**
 * @param {string[]} argv
 * @param {NodeJS.ProcessEnv} env
 * @returns {number} 종료 코드
 */
function main(argv, env) {
  const github = argv.includes('--github')
  const dir = argv.find((arg) => !arg.startsWith('--')) ?? '.lighthouseci'
  const { lhrs, skipped, assertions } = loadResults(dir)
  for (const name of skipped) console.warn(`Lighthouse 결과를 읽지 못해 건너뜀: ${dir}/${name}`)

  if (lhrs.length === 0) {
    console.error(
      `Lighthouse 결과가 없습니다: ${dir}/lhr-*.json — pnpm perf:lighthouse 를 먼저 돌립니다`,
    )
    if (github) process.stdout.write('::warning title=Lighthouse::결과 없음 (수집 실패)\n')
    return 1
  }

  const { text, warningCount } = buildReport({ lhrs, assertions })
  if (!github) {
    process.stdout.write(text)
    return 0
  }
  const summaryPath = env.GITHUB_STEP_SUMMARY
  if (!summaryPath) {
    console.error('--github 는 GITHUB_STEP_SUMMARY 가 있어야 한다')
    return 1
  }
  appendFileSync(summaryPath, text)
  if (warningCount > 0) {
    // GitHub 은 stdout 의 `::warning` 줄을 체크 화면 알림으로 바꾼다
    process.stdout.write(
      `::warning title=Lighthouse::예산 경고 ${warningCount}건 (차단하지 않음)\n`,
    )
  }
  return 0
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exitCode = main(process.argv.slice(2), process.env)
}
