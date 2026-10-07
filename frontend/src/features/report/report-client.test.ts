import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  agreeHealthConsent,
  loginWithEmail,
  logout,
  resetMockSession,
  withdrawHealthConsent,
} from '@/features/auth/auth-client'
import { consentFor } from '@/features/auth/legal'
import { reloadMemberRegion } from '@/features/auth/member-info'
import { ApiError, unavailableError } from '@/lib/api/api-error'
import { getSessionSnapshot, refreshSession } from '@/lib/session/session-store'

import { setCurrentReport } from './current-report'
import { deleteCurrentReport, putCurrentReport } from './report-api'
import {
  cancelReport,
  getSubmittedReport,
  submitReport,
  subscribeSubmittedReport,
  updateReport,
} from './report-client'
import type { SubmittedReport } from './types'

// 실데이터 갈래는 요청 · 저장소 · 세션을 바꿔 끼워 본다. 목 갈래는 이것들을 부르지 않는다
vi.mock('./report-api', () => ({ putCurrentReport: vi.fn(), deleteCurrentReport: vi.fn() }))
vi.mock('./current-report', () => ({ setCurrentReport: vi.fn() }))
vi.mock('@/features/auth/member-info', () => ({
  reloadMemberRegion: vi.fn(),
  setMemberRegion: vi.fn(),
}))
vi.mock('@/lib/session/session-store', () => ({
  getSessionSnapshot: vi.fn(),
  refreshSession: vi.fn(),
  setSession: vi.fn(),
  clearSession: vi.fn(),
}))

const HEALTH_CONSENT = consentFor('SENSITIVE_HEALTH_INFO')

/** 보낸 결과의 보고. 보내지 못했으면 테스트를 멈춘다 */
function okReport(result: Awaited<ReturnType<typeof submitReport>>): SubmittedReport {
  if (result.status !== 'ok') throw new Error(`보내지 못했다: ${result.status}`)
  return result.report
}

/** 동의한 회원 목 세션을 만든다 (이메일 로그인 → 건강정보 동의) */
async function signInWithConsent() {
  await loginWithEmail('reporter@example.com', 'password1!', 'mock')
  await agreeHealthConsent(HEALTH_CONSENT, 'mock')
}

describe('report-client (목) — 이번 주에 보낸 보고', () => {
  beforeEach(async () => {
    resetMockSession()
    await cancelReport('mock')
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('보내기 전에는 없다', () => {
    expect(getSubmittedReport()).toBeNull()
  })

  it('보내면 모듈 메모리에 남고 구독자에게 알린다 — 화면을 떠났다 돌아와도 읽을 수 있다', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2025, 10, 19))
    const listener = vi.fn()
    const unsubscribe = subscribeSubmittedReport(listener)

    const result = await submitReport({ kind: 'none' }, null, 'mock')

    expect(result).toEqual({
      status: 'ok',
      report: { answer: { kind: 'none' }, reportedLabel: '11월 19일' },
      firstSubmission: true,
    })
    expect(getSubmittedReport()).toBe(okReport(result))
    expect(listener).toHaveBeenCalledTimes(1)
    unsubscribe()
  })

  it('같은 주에 고쳐 보내면 마지막 보고 하나만 남는다', async () => {
    await submitReport({ kind: 'none' }, null, 'mock')
    const second = await updateReport(
      { kind: 'symptom', symptoms: ['gastrointestinal'] },
      null,
      'mock',
    )
    const updated = okReport(second)
    expect(getSubmittedReport()).toBe(updated)
    // 이미 있던 보고를 고쳤다 — 첫 저장이 아니다(화면은 되돌리기를 주지 않는다)
    expect(second).toMatchObject({ firstSubmission: false })
    expect(updated.answer).toEqual({ kind: 'symptom', symptoms: ['gastrointestinal'] })
  })

  it('되돌리면 지우고 구독자에게 알린다', async () => {
    await submitReport({ kind: 'none' }, null, 'mock')
    const listener = vi.fn()
    const unsubscribe = subscribeSubmittedReport(listener)

    await cancelReport('mock')

    expect(getSubmittedReport()).toBeNull()
    expect(listener).toHaveBeenCalledTimes(1)
    unsubscribe()
  })

  it('구독을 끊으면 더 알리지 않는다', async () => {
    const listener = vi.fn()
    subscribeSubmittedReport(listener)()
    await submitReport({ kind: 'none' }, null, 'mock')
    expect(listener).not.toHaveBeenCalled()
  })

  it('로그아웃하면 지운다 — 같은 기기에서 다음에 로그인한 사람에게 보이지 않는다', async () => {
    await signInWithConsent()
    await submitReport({ kind: 'symptom', symptoms: ['respiratory'] }, null, 'mock')
    const listener = vi.fn()
    const unsubscribe = subscribeSubmittedReport(listener)

    await logout('mock')

    expect(getSubmittedReport()).toBeNull()
    expect(listener).toHaveBeenCalled()

    // 다시 로그인 · 동의해도 지난 보고는 돌아오지 않는다
    await signInWithConsent()
    expect(getSubmittedReport()).toBeNull()
    unsubscribe()
  })

  it('건강정보 동의를 철회하면 지운다 (서버도 보낸 보고를 지운다)', async () => {
    await signInWithConsent()
    await submitReport({ kind: 'none' }, null, 'mock')

    await withdrawHealthConsent('mock')

    expect(getSubmittedReport()).toBeNull()
  })

  it('회원 상태가 그대로면 지우지 않는다', async () => {
    await signInWithConsent()
    const sent = await submitReport({ kind: 'none' }, null, 'mock')

    // 이미 동의한 회원이 다시 동의를 보내도 세션 상태는 member 그대로다
    await agreeHealthConsent(HEALTH_CONSENT, 'mock')

    expect(getSubmittedReport()).toBe(okReport(sent))
  })

  it('QA 덮어쓰기(?mock-auth=member)처럼 목 세션이 비회원인 채로 보내도 남는다', async () => {
    const sent = await submitReport({ kind: 'none' }, null, 'mock')
    expect(getSubmittedReport()).toBe(okReport(sent))
  })
})

const MEMBER_ID = '1843956734582784'
const YEOKSAM1 = '11680640'
const SENT: SubmittedReport = { answer: { kind: 'none' }, reportedLabel: '10월 2일' }

function apiError(code: string, status: number): ApiError {
  return new ApiError({ status, code, message: '거절' })
}

describe('report-client (실데이터) — 보내기 · 고치기', () => {
  beforeEach(async () => {
    await cancelReport('mock')
    vi.mocked(putCurrentReport).mockReset()
    vi.mocked(setCurrentReport).mockReset()
    vi.mocked(reloadMemberRegion).mockReset()
    vi.mocked(refreshSession).mockReset().mockResolvedValue(undefined)
    vi.mocked(getSessionSnapshot).mockReturnValue({
      status: 'member',
      summary: { memberId: MEMBER_ID, role: 'USER', pendingConsents: [], reportWritable: true },
    })
  })

  it.each([
    ['처음 보내기', submitReport],
    ['고치기', updateReport],
  ])('%s는 내 동네 코드로 PUT 하고 응답을 저장소에 바로 넣는다', async (_, send) => {
    vi.mocked(putCurrentReport).mockResolvedValue({
      report: SENT,
      isoWeek: '2026-W40',
      firstSubmission: true,
    })

    const result = await send({ kind: 'none' }, YEOKSAM1, 'api')

    expect(result).toEqual({ status: 'ok', report: SENT, firstSubmission: true })
    expect(putCurrentReport).toHaveBeenCalledWith(YEOKSAM1, { kind: 'none' })
    expect(setCurrentReport).toHaveBeenCalledWith(MEMBER_ID, SENT, '2026-W40')
    // 목 보고는 건드리지 않는다
    expect(getSubmittedReport()).toBeNull()
  })

  it('처음 저장인지는 서버 응답을 따른다 — 이 탭이 모르던 보고를 고쳤으면 firstSubmission false', async () => {
    vi.mocked(putCurrentReport).mockResolvedValue({
      report: SENT,
      isoWeek: '2026-W40',
      firstSubmission: false,
    })
    await expect(submitReport({ kind: 'none' }, YEOKSAM1, 'api')).resolves.toMatchObject({
      status: 'ok',
      firstSubmission: false,
    })
  })

  it('보고 권한이 없으면(SECURITY_006) 세션 요약을 다시 맞추고 consent-required 다', async () => {
    vi.mocked(putCurrentReport).mockRejectedValue(apiError('SECURITY_006', 403))

    await expect(submitReport({ kind: 'none' }, YEOKSAM1, 'api')).resolves.toEqual({
      status: 'consent-required',
    })
    expect(refreshSession).toHaveBeenCalledTimes(1)
    expect(setCurrentReport).not.toHaveBeenCalled()
  })

  it.each(['REPORT_003', 'REPORT_002'])(
    '동네를 쓸 수 없으면(%s) 내 동네를 다시 읽게 하고 region-changed 다',
    async (code) => {
      vi.mocked(putCurrentReport).mockRejectedValue(apiError(code, 400))

      await expect(submitReport({ kind: 'none' }, YEOKSAM1, 'api')).resolves.toEqual({
        status: 'region-changed',
      })
      expect(reloadMemberRegion).toHaveBeenCalledTimes(1)
      expect(setCurrentReport).not.toHaveBeenCalled()
    },
  )

  it.each([
    ['동시 제출', apiError('REPORT_001', 409)],
    ['검증 오류', apiError('REPORT_102', 400)],
    ['알 수 없는 증상군', apiError('REPORT_100', 400)],
    ['일시 장애', unavailableError('network', 0)],
  ])('%s는 거부한다 — 화면은 잠시 뒤 다시 보내라고 알린다', async (_, error) => {
    vi.mocked(putCurrentReport).mockRejectedValue(error)

    await expect(submitReport({ kind: 'none' }, YEOKSAM1, 'api')).rejects.toBe(error)
    expect(refreshSession).not.toHaveBeenCalled()
    expect(reloadMemberRegion).not.toHaveBeenCalled()
    expect(setCurrentReport).not.toHaveBeenCalled()
  })

  it('세션이 회원이 아니거나 보고 동네가 없으면 보내지 않고 거부한다', async () => {
    await expect(submitReport({ kind: 'none' }, null, 'api')).rejects.toThrow('no district')

    vi.mocked(getSessionSnapshot).mockReturnValue({ status: 'guest' })
    await expect(submitReport({ kind: 'none' }, YEOKSAM1, 'api')).rejects.toThrow(
      'no member session',
    )
    expect(putCurrentReport).not.toHaveBeenCalled()
  })
})

describe('report-client (실데이터) — 되돌리기', () => {
  beforeEach(() => {
    vi.mocked(deleteCurrentReport).mockReset()
    vi.mocked(setCurrentReport).mockReset()
    vi.mocked(refreshSession).mockReset().mockResolvedValue(undefined)
    vi.mocked(getSessionSnapshot).mockReturnValue({
      status: 'member',
      summary: { memberId: MEMBER_ID, role: 'USER', pendingConsents: [], reportWritable: true },
    })
  })

  it('DELETE 하고 저장소를 비운다', async () => {
    vi.mocked(deleteCurrentReport).mockResolvedValue(undefined)

    await cancelReport('api')

    expect(deleteCurrentReport).toHaveBeenCalledTimes(1)
    expect(setCurrentReport).toHaveBeenCalledWith(MEMBER_ID, null)
  })

  it('실패하면 거부하고 저장소를 그대로 둔다', async () => {
    const error = unavailableError('timeout', 0)
    vi.mocked(deleteCurrentReport).mockRejectedValue(error)

    await expect(cancelReport('api')).rejects.toBe(error)
    expect(setCurrentReport).not.toHaveBeenCalled()
    expect(refreshSession).not.toHaveBeenCalled()
  })

  it('보고 권한이 없으면 세션 요약을 다시 맞춘 뒤 거부한다', async () => {
    vi.mocked(deleteCurrentReport).mockRejectedValue(apiError('SECURITY_006', 403))

    await expect(cancelReport('api')).rejects.toBeInstanceOf(ApiError)
    expect(refreshSession).toHaveBeenCalledTimes(1)
    expect(setCurrentReport).not.toHaveBeenCalled()
  })

  it('세션이 회원이 아니면 보내지 않고 거부한다', async () => {
    vi.mocked(getSessionSnapshot).mockReturnValue({ status: 'guest' })
    await expect(cancelReport('api')).rejects.toThrow('no member session')
    expect(deleteCurrentReport).not.toHaveBeenCalled()
  })
})
