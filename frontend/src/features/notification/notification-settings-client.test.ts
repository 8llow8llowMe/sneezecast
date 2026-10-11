import { beforeEach, describe, expect, it, vi } from 'vitest'

import { loginWithEmail, logout, resetMockSession } from '@/features/auth/auth-client'
import { ApiError, unavailableError } from '@/lib/api/api-error'
import { apiRequest } from '@/lib/api/client'

import {
  getNotificationSettings,
  NOTIFICATION_SETTINGS_PATH,
  parseMockNotificationScenario,
  resetMockNotificationSettings,
  updateNotificationSetting,
} from './notification-settings-client'

vi.mock('@/lib/api/client', () => ({ apiRequest: vi.fn() }))

const ALL_OFF = { weeklyReport: false, regionNotice: false }

function apiError(code: string, status: number): ApiError {
  return new ApiError({ status, code, message: '거절' })
}

beforeEach(() => {
  resetMockSession()
  resetMockNotificationSettings()
  vi.mocked(apiRequest).mockReset()
})

describe('알림 설정 클라이언트 (실데이터)', () => {
  it('읽기는 GET 이고 두 값만 옮긴다. 목 설정은 건드리지 않는다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce({
      weeklyReport: true,
      regionNotice: false,
      extra: 'x',
    })

    expect(await getNotificationSettings('api', { scenario: 'on' })).toEqual({
      weeklyReport: true,
      regionNotice: false,
    })
    expect(apiRequest).toHaveBeenCalledWith(NOTIFICATION_SETTINGS_PATH)
    expect(NOTIFICATION_SETTINGS_PATH).toBe('/api/v1/members/me/notification-settings')
    expect(await getNotificationSettings('mock')).toEqual(ALL_OFF)
  })

  it('읽기 실패(일시 장애 · 인증 오류)는 거부한다', async () => {
    vi.mocked(apiRequest).mockRejectedValueOnce(unavailableError('network', 0))
    await expect(getNotificationSettings('api')).rejects.toMatchObject({ code: 'UNAVAILABLE' })
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError('SECURITY_001', 401))
    await expect(getNotificationSettings('api')).rejects.toMatchObject({ code: 'SECURITY_001' })
  })

  it('바꾸기는 그 항목만 PATCH 하고 바뀐 뒤의 설정 전부를 그대로 준다 — 다시 읽지 않는다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce({ weeklyReport: true, regionNotice: true })

    expect(await updateNotificationSetting('regionNotice', true, 'api')).toEqual({
      weeklyReport: true,
      regionNotice: true,
    })
    expect(apiRequest).toHaveBeenCalledTimes(1)
    expect(apiRequest).toHaveBeenCalledWith(NOTIFICATION_SETTINGS_PATH, {
      method: 'PATCH',
      body: { regionNotice: true },
    })
  })

  it('첫 변경이 겹치면(MEMBER_013) 한 번만 다시 보낸다', async () => {
    vi.mocked(apiRequest)
      .mockRejectedValueOnce(apiError('MEMBER_013', 409))
      .mockResolvedValueOnce({ weeklyReport: false, regionNotice: false })
    expect(await updateNotificationSetting('weeklyReport', false, 'api')).toEqual(ALL_OFF)
    expect(apiRequest).toHaveBeenCalledTimes(2)

    vi.mocked(apiRequest).mockReset()
    vi.mocked(apiRequest)
      .mockRejectedValueOnce(apiError('MEMBER_013', 409))
      .mockRejectedValueOnce(apiError('MEMBER_013', 409))
    await expect(updateNotificationSetting('weeklyReport', true, 'api')).rejects.toMatchObject({
      code: 'MEMBER_013',
    })
    expect(apiRequest).toHaveBeenCalledTimes(2)
  })

  it('그 밖의 실패(검증 · 일시 장애)는 다시 보내지 않고 거부한다', async () => {
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError('MEMBER_112', 400))
    await expect(updateNotificationSetting('weeklyReport', true, 'api')).rejects.toMatchObject({
      code: 'MEMBER_112',
    })
    vi.mocked(apiRequest).mockRejectedValueOnce(unavailableError('network', 0))
    await expect(updateNotificationSetting('weeklyReport', true, 'api')).rejects.toMatchObject({
      code: 'UNAVAILABLE',
    })
    expect(apiRequest).toHaveBeenCalledTimes(2)
  })
})

describe('알림 설정 클라이언트 (목)', () => {
  it('처음은 모두 꺼짐이다(동의한 항목만 보낸다). 켜고 끈 항목만 바뀌고 설정 전부를 준다', async () => {
    expect(await getNotificationSettings('mock')).toEqual(ALL_OFF)

    expect(await updateNotificationSetting('regionNotice', true, 'mock')).toEqual({
      weeklyReport: false,
      regionNotice: true,
    })
    expect(await updateNotificationSetting('weeklyReport', true, 'mock')).toEqual({
      weeklyReport: true,
      regionNotice: true,
    })
    await updateNotificationSetting('regionNotice', false, 'mock')
    expect(await getNotificationSettings('mock')).toEqual({
      weeklyReport: true,
      regionNotice: false,
    })
  })

  it('받은 값을 고쳐도 목 서버 값은 바뀌지 않는다', async () => {
    const settings = await getNotificationSettings('mock')
    ;(settings as Record<string, boolean>).weeklyReport = true
    expect(await getNotificationSettings('mock')).toEqual(ALL_OFF)
  })

  it('재현 on 은 모두 켜짐으로 다시 시작하고, fail 은 바꾸기가 실패한다(값 그대로). 재현 없이 다시 읽으면 fail 만 풀린다', async () => {
    await updateNotificationSetting('weeklyReport', true, 'mock')
    expect(await getNotificationSettings('mock', { scenario: 'on' })).toEqual({
      weeklyReport: true,
      regionNotice: true,
    })

    expect(await getNotificationSettings('mock', { scenario: 'fail' })).toEqual(ALL_OFF)
    await expect(updateNotificationSetting('regionNotice', true, 'mock')).rejects.toThrow()
    expect(await getNotificationSettings('mock')).toEqual(ALL_OFF)

    expect(await updateNotificationSetting('regionNotice', true, 'mock')).toEqual({
      weeklyReport: false,
      regionNotice: true,
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
    expect(await getNotificationSettings('mock')).toEqual(ALL_OFF)
  })
})
