import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  agreeHealthConsent,
  loginWithEmail,
  logout,
  resetMockSession,
  withdrawHealthConsent,
} from '@/features/auth/auth-client'
import { consentFor } from '@/features/auth/legal'

import {
  cancelReport,
  getSubmittedReport,
  submitReport,
  subscribeSubmittedReport,
  updateReport,
} from './report-client'

const HEALTH_CONSENT = consentFor('SENSITIVE_HEALTH_INFO')

/** 동의한 회원 목 세션을 만든다 (이메일 로그인 → 건강정보 동의) */
async function signInWithConsent() {
  await loginWithEmail('reporter@example.com', 'password1!')
  await agreeHealthConsent(HEALTH_CONSENT)
}

describe('report-client (목) — 이번 주에 보낸 보고', () => {
  beforeEach(async () => {
    resetMockSession()
    await cancelReport()
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

    const result = await submitReport({ kind: 'none' })

    expect(result).toEqual({ answer: { kind: 'none' }, reportedLabel: '11월 19일' })
    expect(getSubmittedReport()).toBe(result)
    expect(listener).toHaveBeenCalledTimes(1)
    unsubscribe()
  })

  it('같은 주에 고쳐 보내면 마지막 보고 하나만 남는다', async () => {
    await submitReport({ kind: 'none' })
    const updated = await updateReport({ kind: 'symptom', symptoms: ['gastrointestinal'] })
    expect(getSubmittedReport()).toBe(updated)
    expect(updated.answer).toEqual({ kind: 'symptom', symptoms: ['gastrointestinal'] })
  })

  it('되돌리면 지우고 구독자에게 알린다', async () => {
    await submitReport({ kind: 'none' })
    const listener = vi.fn()
    const unsubscribe = subscribeSubmittedReport(listener)

    await cancelReport()

    expect(getSubmittedReport()).toBeNull()
    expect(listener).toHaveBeenCalledTimes(1)
    unsubscribe()
  })

  it('구독을 끊으면 더 알리지 않는다', async () => {
    const listener = vi.fn()
    subscribeSubmittedReport(listener)()
    await submitReport({ kind: 'none' })
    expect(listener).not.toHaveBeenCalled()
  })

  it('로그아웃하면 지운다 — 같은 기기에서 다음에 로그인한 사람에게 보이지 않는다', async () => {
    await signInWithConsent()
    await submitReport({ kind: 'symptom', symptoms: ['respiratory'] })
    const listener = vi.fn()
    const unsubscribe = subscribeSubmittedReport(listener)

    await logout()

    expect(getSubmittedReport()).toBeNull()
    expect(listener).toHaveBeenCalled()

    // 다시 로그인 · 동의해도 지난 보고는 돌아오지 않는다
    await signInWithConsent()
    expect(getSubmittedReport()).toBeNull()
    unsubscribe()
  })

  it('건강정보 동의를 철회하면 지운다 (서버도 보낸 보고를 지운다)', async () => {
    await signInWithConsent()
    await submitReport({ kind: 'none' })

    await withdrawHealthConsent()

    expect(getSubmittedReport()).toBeNull()
  })

  it('회원 상태가 그대로면 지우지 않는다', async () => {
    await signInWithConsent()
    const sent = await submitReport({ kind: 'none' })

    // 이미 동의한 회원이 다시 동의를 보내도 세션 상태는 member 그대로다
    await agreeHealthConsent(HEALTH_CONSENT)

    expect(getSubmittedReport()).toBe(sent)
  })

  it('QA 덮어쓰기(?mock-auth=member)처럼 목 세션이 비회원인 채로 보내도 남는다', async () => {
    const sent = await submitReport({ kind: 'none' })
    expect(getSubmittedReport()).toBe(sent)
  })
})
