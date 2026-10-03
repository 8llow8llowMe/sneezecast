// @ts-check
/**
 * Lighthouse 측정 전 확인 (#177). `pnpm perf:lighthouse` 와 frontend-ci 가 `lhci autorun` 앞에서 부른다.
 *
 * **lhci 는 서버가 뜨지 않아도 멈추지 않는다.** `startServerCommand` 가 준비 문구(`Ready in`)를 내지 않으면 시간 초과 경고만
 * 찍고 그대로 URL 을 잰다(`@lhci/cli` collect.js `startServerAndDetermineUrls`). 그래서
 * - 3100 포트에 다른 서버가 떠 있으면 `next start` 는 포트를 못 잡고 끝나는데, lhci 는 **그 다른 서버를 잰다.**
 * - 빌드(`.next/BUILD_ID`)가 없으면 `next start` 가 바로 끝나고, lhci 는 60초 기다린 뒤 닫힌 포트를 재 실행마다 실패한다.
 * 둘 다 여기서 미리 막는다(종료 코드 1).
 *
 * **지난 결과를 지운다.** `lhci collect` 는 `.lighthouseci/` 의 `lhr-*.json` · `lhr-*.html` 만 지우고
 * `assertion-results.json` 과 업로드 폴더(`report/`)는 남긴다(`@lhci/utils` saved-reports.js `clearSavedReportsAndLHRs`).
 * 수집이 중간에 실패해 `assert` 가 돌지 않으면 지난 예산 결과가 이번 요약에 섞이므로, 확인을 통과하면 그 둘을 지운다.
 * 확인에 걸리면 아무것도 지우지 않는다(지난 결과를 다시 볼 수 있게).
 *
 * 포트는 lighthouserc.yml 의 `next start -p 3100` · URL 과 같아야 한다(lighthouse-preflight.test.mjs 가 맞춰 본다).
 */

import { existsSync, rmSync } from 'node:fs'
import { connect } from 'node:net'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'

export const LIGHTHOUSE_PORT = 3100

/** `localhost` 가 어느 쪽으로 풀려도 잡히게 둘 다 본다 */
const LOCAL_HOSTS = ['127.0.0.1', '::1']

/**
 * 그 주소 · 포트에 연결을 받는 서버가 있는지. 연결 오류(거절 · IPv6 없음)와 시간 초과는 "없음" 이다.
 * @param {number} port
 * @param {string} host
 * @param {number} [timeoutMs]
 * @returns {Promise<boolean>}
 */
export function isPortOpen(port, host, timeoutMs = 1000) {
  return new Promise((resolve) => {
    const socket = connect({ port, host })
    const done = (/** @type {boolean} */ open) => {
      socket.destroy()
      resolve(open)
    }
    socket.setTimeout(timeoutMs, () => done(false))
    socket.once('connect', () => done(true))
    socket.once('error', () => done(false))
  })
}

/**
 * `next build` 산출물이 있는지. `next dev` 는 `BUILD_ID` 를 남기지 않는다.
 * @param {string} nextDir
 */
export function hasProductionBuild(nextDir) {
  return existsSync(join(nextDir, 'BUILD_ID'))
}

/**
 * `lhci collect` 가 지우지 않는 지난 결과(예산 확인 결과 · 업로드 폴더)를 지운다. 지운 경로를 돌려준다.
 * @param {string} resultsDir
 * @returns {string[]}
 */
export function clearStaleResults(resultsDir) {
  const targets = [join(resultsDir, 'assertion-results.json'), join(resultsDir, 'report')]
  const removed = targets.filter((target) => existsSync(target))
  for (const target of removed) rmSync(target, { recursive: true, force: true })
  return removed
}

/**
 * @param {{ port: number, hosts?: ReadonlyArray<string>, nextDir: string, resultsDir: string }} options
 * @returns {Promise<{ problems: string[], removed: string[] }>}
 */
export async function preflight({ port, hosts = LOCAL_HOSTS, nextDir, resultsDir }) {
  /** @type {string[]} */
  const problems = []
  const open = await Promise.all(hosts.map((host) => isPortOpen(port, host)))
  if (open.some(Boolean)) {
    problems.push(
      `${port} 포트에 이미 서버가 떠 있습니다. 끄고 다시 돌립니다 — 그대로 재면 그 서버를 잽니다.`,
    )
  }
  if (!hasProductionBuild(nextDir)) {
    problems.push(`프로덕션 빌드가 없습니다(${nextDir}/BUILD_ID). pnpm build 를 먼저 돌립니다.`)
  }
  if (problems.length > 0) return { problems, removed: [] }
  return { problems, removed: clearStaleResults(resultsDir) }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const { problems } = await preflight({
    port: LIGHTHOUSE_PORT,
    nextDir: '.next',
    resultsDir: '.lighthouseci',
  })
  for (const problem of problems) console.error(`Lighthouse 측정 전 확인 실패: ${problem}`)
  process.exitCode = problems.length > 0 ? 1 : 0
}
