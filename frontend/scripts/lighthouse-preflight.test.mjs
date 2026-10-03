import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { afterEach, describe, expect, it } from 'vitest'

import {
  clearStaleResults,
  hasProductionBuild,
  isPortOpen,
  LIGHTHOUSE_PORT,
  preflight,
} from './lighthouse-preflight.mjs'

/** @type {Array<() => void>} */
const cleanups = []
afterEach(() => {
  for (const cleanup of cleanups.splice(0)) cleanup()
})

function tempDir() {
  const dir = mkdtempSync(join(tmpdir(), 'lhci-preflight-'))
  cleanups.push(() => rmSync(dir, { recursive: true, force: true }))
  return dir
}

/** 127.0.0.1 의 빈 포트에 서버를 띄우고 그 포트를 돌려준다 */
async function listen() {
  const server = createServer()
  await new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(undefined)))
  cleanups.push(() => server.close())
  const address = server.address()
  if (address === null || typeof address === 'string') throw new Error('포트를 못 얻음')
  return { port: address.port, close: () => new Promise((resolve) => server.close(resolve)) }
}

/** 빌드 · 지난 결과가 있는 폴더 */
function workspace({ build = true } = {}) {
  const root = tempDir()
  const nextDir = join(root, '.next')
  const resultsDir = join(root, '.lighthouseci')
  mkdirSync(join(resultsDir, 'report'), { recursive: true })
  writeFileSync(join(resultsDir, 'assertion-results.json'), '[]')
  writeFileSync(join(resultsDir, 'report', 'manifest.json'), '[]')
  writeFileSync(join(resultsDir, 'lhr-1.json'), '{}')
  if (build) {
    mkdirSync(nextDir)
    writeFileSync(join(nextDir, 'BUILD_ID'), 'test')
  }
  return { nextDir, resultsDir }
}

describe('isPortOpen', () => {
  it('서버가 받으면 true, 닫히면 false 다', async () => {
    const { port, close } = await listen()
    expect(await isPortOpen(port, '127.0.0.1')).toBe(true)
    await close()
    expect(await isPortOpen(port, '127.0.0.1')).toBe(false)
  })
})

describe('hasProductionBuild', () => {
  it('BUILD_ID 가 있을 때만 true 다', () => {
    expect(hasProductionBuild(workspace().nextDir)).toBe(true)
    expect(hasProductionBuild(workspace({ build: false }).nextDir)).toBe(false)
  })
})

describe('clearStaleResults', () => {
  it('예산 확인 결과와 업로드 폴더만 지우고 lhr 파일은 남긴다(그건 collect 가 지운다)', () => {
    const { resultsDir } = workspace()
    expect(clearStaleResults(resultsDir)).toHaveLength(2)
    expect(existsSync(join(resultsDir, 'assertion-results.json'))).toBe(false)
    expect(existsSync(join(resultsDir, 'report'))).toBe(false)
    expect(existsSync(join(resultsDir, 'lhr-1.json'))).toBe(true)
  })

  it('폴더가 없어도 던지지 않는다', () => {
    expect(clearStaleResults(join(tempDir(), 'none'))).toEqual([])
  })
})

describe('preflight', () => {
  it('포트가 차 있고 빌드가 없으면 둘 다 알리고 아무것도 지우지 않는다', async () => {
    const { port } = await listen()
    const { nextDir, resultsDir } = workspace({ build: false })
    const result = await preflight({ port, hosts: ['127.0.0.1'], nextDir, resultsDir })
    expect(result.problems).toHaveLength(2)
    expect(result.problems[0]).toContain(`${port} 포트`)
    expect(result.problems[1]).toContain('pnpm build')
    expect(result.removed).toEqual([])
    expect(existsSync(join(resultsDir, 'assertion-results.json'))).toBe(true)
  })

  it('통과하면 지난 결과를 지운다', async () => {
    const { port, close } = await listen()
    await close()
    const { nextDir, resultsDir } = workspace()
    const result = await preflight({ port, hosts: ['127.0.0.1'], nextDir, resultsDir })
    expect(result.problems).toEqual([])
    expect(result.removed).toHaveLength(2)
  })
})

describe('lighthouserc.yml', () => {
  it('서버 포트와 측정 URL 이 확인하는 포트와 같다', () => {
    const config = readFileSync(new URL('../lighthouserc.yml', import.meta.url), 'utf8')
    expect(config).toContain(`next start -p ${LIGHTHOUSE_PORT}`)
    const ports = [...config.matchAll(/http:\/\/localhost:(\d+)\//g)].map((match) => match[1])
    expect(ports.length).toBeGreaterThan(0)
    expect(new Set(ports)).toEqual(new Set([String(LIGHTHOUSE_PORT)]))
  })
})
