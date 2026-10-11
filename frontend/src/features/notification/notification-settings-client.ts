import { getMockSession, subscribeMockSession } from '@/features/auth/auth-client'
import { ApiError } from '@/lib/api/api-error'
import { apiRequest } from '@/lib/api/client'
import type { DataSource } from '@/lib/data-source'

import type { NotificationTopic } from './notification-topics'

/* ── 알림 설정 (S10 `/me/notifications`, #195 · 연동 #250) ─────────────────────────────────────────
 *
 * 회원이 받고 싶은 알림 항목(켜기 · 끄기). 계약은 백엔드 #218 의 `GET · PATCH /api/v1/members/me/notification-settings` 다
 * (docs/api-contract-draft.md "알림 설정", 정본 backend/docs/modules.md "알림 설정"). 출처(`DataSource`)는 부르는 화면이 넘긴다.
 *
 * - **알림을 보내는 일과 무관하다.** 웹 푸시 구독 · 권한 요청 · 서비스 워커 등록은 2단계라 여기서 하지 않는다
 *   (`Notification.requestPermission` · `pushManager.subscribe` 를 부르지 않는다). 켠 항목도 지금은 알림이 오지 않는다 —
 *   화면이 그렇게 알린다
 * - 실데이터: 읽기는 `GET`, 바꾸기는 그 항목만 `PATCH { <항목>: boolean }` 이고 둘 다 **설정 전부**를 준다(화면이 다시 읽지 않는다).
 *   `MEMBER_013`(같은 계정의 첫 변경이 겹침 — 다시 보내면 풀림)은 한 번 다시 보낸다. 그 밖(검증 · 일시 장애 · 인증 오류 ·
 *   두 번째 경합)은 거부한다 — 화면은 "잠시 뒤 다시 시도" 로 알린다
 * - 목: 모듈 메모리의 설정(목 서버 흉내). 관심 동네 · 로그인한 기기 목록과 같게 목 세션이 **비회원이 될 때만** 지운다
 *
 * 처음 값은 **모두 꺼짐**이다(서버도 같다) — 알림은 동의한 사용자에게만 보낸다(루트 `CLAUDE.md` "공식 정보와 안내").
 * 시안(Settings)의 켜진 모양은 목 재현 `on` 으로 본다.
 */

export const NOTIFICATION_SETTINGS_PATH = '/api/v1/members/me/notification-settings'

/** 항목별 켜짐. 응답 모양 그대로다 */
export type NotificationSettings = Readonly<Record<NotificationTopic, boolean>>

/** 목 재현 쿼리. 목데이터 모드에서만 쓴다 */
export const MOCK_NOTIFICATIONS_PARAM = 'mock-notifications'

/** 목 재현: 모두 켜짐(시안 Settings 모양) · 바꾸기가 실패함 */
const MOCK_SCENARIOS = ['on', 'fail'] as const
export type MockNotificationScenario = (typeof MOCK_SCENARIOS)[number]

/** 쿼리 값을 목 재현으로 읽는다. 모르는 값이면 null(모두 꺼짐)이다 */
export function parseMockNotificationScenario(
  value: string | null,
): MockNotificationScenario | null {
  return MOCK_SCENARIOS.find((scenario) => scenario === value) ?? null
}

type MockStore = { settings: Record<NotificationTopic, boolean>; failWrites: boolean }

let mockStore: MockStore | null = null

// 목 세션이 비회원이 되면(로그아웃 · 탈퇴 · 동의 철회 · 로그인 만료) 지운다. 같은 기기에서 다음에 로그인한 사람에게 보이지 않게 한다
subscribeMockSession(() => {
  if (getMockSession() === 'guest') mockStore = null
})

function seed(scenario: MockNotificationScenario | null): MockStore {
  const on = scenario === 'on'
  return { settings: { weeklyReport: on, regionNotice: on }, failWrites: scenario === 'fail' }
}

function store(): MockStore {
  mockStore ??= seed(null)
  return mockStore
}

/* ── 실데이터 ───────────────────────────────────────────────────────────────────── */

/** 같은 계정의 첫 변경이 겹쳤다(409). 다시 보내면 풀린다 */
const UPDATE_CONFLICT = 'MEMBER_013'

function toSettings({ weeklyReport, regionNotice }: NotificationSettings): NotificationSettings {
  // 화면이 쓰는 두 값만 옮긴다
  return { weeklyReport, regionNotice }
}

function patchSetting(topic: NotificationTopic, enabled: boolean): Promise<NotificationSettings> {
  return apiRequest<NotificationSettings>(NOTIFICATION_SETTINGS_PATH, {
    method: 'PATCH',
    body: { [topic]: enabled },
  }).then(toSettings)
}

/** 경합(`MEMBER_013`)이면 한 번만 다시 보낸다. 두 번째 결과는 그대로다 */
async function updateSettingApi(
  topic: NotificationTopic,
  enabled: boolean,
): Promise<NotificationSettings> {
  try {
    return await patchSetting(topic, enabled)
  } catch (error) {
    if (!(error instanceof ApiError) || error.code !== UPDATE_CONFLICT) throw error
    return patchSetting(topic, enabled)
  }
}

/* ── 목 ─────────────────────────────────────────────────────────────────────────── */

/** 화면이 값을 고쳐도 목 서버 값이 바뀌지 않게 복사해 준다 */
function snapshot(): NotificationSettings {
  return { ...store().settings }
}

/** 목 서버의 응답처럼 Promise 로 준다. `answer` 가 던지면 거부한다 */
function respond<T>(answer: () => T): Promise<T> {
  return new Promise((resolve) => resolve(answer()))
}

/**
 * 알림 설정을 읽는다. 실데이터는 `GET` 이고 실패는 거부한다. 목: `scenario` 는 목 재현이다 — 주어지면 목 설정을 그 상태로 다시 시작한다(화면을 열 때마다).
 * 주어지지 않으면 설정은 그대로 두고 실패 재현(`fail`)만 끈다. 목은 실패하지 않는다
 */
export function getNotificationSettings(
  source: DataSource,
  { scenario = null }: { scenario?: MockNotificationScenario | null } = {},
): Promise<NotificationSettings> {
  if (source === 'api')
    return apiRequest<NotificationSettings>(NOTIFICATION_SETTINGS_PATH).then(toSettings)
  return respond(() => {
    if (scenario) mockStore = seed(scenario)
    else store().failWrites = false
    return snapshot()
  })
}

/**
 * 한 항목을 켜거나 끈다. 바뀐 뒤의 설정 전부를 준다 — 다른 탭에서 다른 항목을 바꿨으면 화면이 맞춘다.
 * 응답 전에 화면을 떠나도 서버(목 서버)에서는 끝난 일이다. 실패하면 거부한다(실데이터 오류 처리는 위 머리 주석, 목 재현 `fail`)
 */
export function updateNotificationSetting(
  topic: NotificationTopic,
  enabled: boolean,
  source: DataSource,
): Promise<NotificationSettings> {
  if (source === 'api') return updateSettingApi(topic, enabled)
  return respond(() => {
    const current = store()
    if (current.failWrites) throw new Error('mock: update notification setting failed')
    current.settings[topic] = enabled
    return snapshot()
  })
}

/** 테스트에서 목 설정을 처음(모두 꺼짐)으로 되돌린다. 화면 코드는 부르지 않는다 */
export function resetMockNotificationSettings(): void {
  mockStore = null
}
