import { beforeEach, describe, expect, it } from 'vitest'

import { loginWithEmail, logout, resetMockSession } from '@/features/auth/auth-client'

import {
  getNotificationSettings,
  parseMockNotificationScenario,
  resetMockNotificationSettings,
  updateNotificationSetting,
} from './notification-settings-client'

const ALL_OFF = { weeklyReport: false, regionNotice: false }

beforeEach(() => {
  resetMockSession()
  resetMockNotificationSettings()
})

describe('알림 설정 클라이언트 (실데이터)', () => {
  it('BE 미정이라 요청하지 않고 불러올 수 없음 · 저장할 수 없음이다 — 켠 것처럼 보이지 않는다', async () => {
    expect(await getNotificationSettings('api')).toEqual({ status: 'unavailable' })
    expect(await updateNotificationSetting('weeklyReport', true, 'api')).toEqual({
      status: 'unavailable',
    })
    // 목 설정도 건드리지 않는다
    expect(await getNotificationSettings('mock')).toEqual({ status: 'ready', settings: ALL_OFF })
  })
})

describe('알림 설정 클라이언트 (목)', () => {
  it('처음은 모두 꺼짐이다(동의한 항목만 보낸다). 켜고 끈 항목만 바뀌고 설정 전부를 준다', async () => {
    expect(await getNotificationSettings('mock')).toEqual({ status: 'ready', settings: ALL_OFF })

    expect(await updateNotificationSetting('regionNotice', true, 'mock')).toEqual({
      status: 'ready',
      settings: { weeklyReport: false, regionNotice: true },
    })
    expect(await updateNotificationSetting('weeklyReport', true, 'mock')).toEqual({
      status: 'ready',
      settings: { weeklyReport: true, regionNotice: true },
    })
    await updateNotificationSetting('regionNotice', false, 'mock')
    expect(await getNotificationSettings('mock')).toEqual({
      status: 'ready',
      settings: { weeklyReport: true, regionNotice: false },
    })
  })

  it('받은 값을 고쳐도 목 서버 값은 바뀌지 않는다', async () => {
    const result = await getNotificationSettings('mock')
    if (result.status !== 'ready') throw new Error('목은 늘 읽는다')
    ;(result.settings as Record<string, boolean>).weeklyReport = true
    expect(await getNotificationSettings('mock')).toEqual({ status: 'ready', settings: ALL_OFF })
  })

  it('재현 on 은 모두 켜짐으로 다시 시작하고, fail 은 바꾸기가 실패한다(값 그대로). 재현 없이 다시 읽으면 fail 만 풀린다', async () => {
    await updateNotificationSetting('weeklyReport', true, 'mock')
    expect(await getNotificationSettings('mock', { scenario: 'on' })).toEqual({
      status: 'ready',
      settings: { weeklyReport: true, regionNotice: true },
    })

    expect(await getNotificationSettings('mock', { scenario: 'fail' })).toEqual({
      status: 'ready',
      settings: ALL_OFF,
    })
    await expect(updateNotificationSetting('regionNotice', true, 'mock')).rejects.toThrow()
    expect(await getNotificationSettings('mock')).toEqual({ status: 'ready', settings: ALL_OFF })

    expect(await updateNotificationSetting('regionNotice', true, 'mock')).toEqual({
      status: 'ready',
      settings: { weeklyReport: false, regionNotice: true },
    })
  })

  it('모르는 재현 값은 무시한다', () => {
    expect(parseMockNotificationScenario('on')).toBe('on')
    expect(parseMockNotificationScenario('fail')).toBe('fail')
    expect(parseMockNotificationScenario('ON')).toBeNull()
    expect(parseMockNotificationScenario(null)).toBeNull()
  })

  it('목 세션이 비회원이 되면 지운다 — 다음 로그인은 모두 꺼짐부터다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    await updateNotificationSetting('weeklyReport', true, 'mock')

    await logout('mock')
    expect(await getNotificationSettings('mock')).toEqual({ status: 'ready', settings: ALL_OFF })
  })
})
