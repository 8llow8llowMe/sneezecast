'use client'

import { useEffect, useState } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'

import { AlertBox } from '@/components/alert-box'
import type { MockAuthState } from '@/features/auth/auth-client'
import { reportButtonLabel } from '@/features/home/report-gate'
import {
  getNotificationSettings,
  MOCK_NOTIFICATIONS_PARAM,
  type NotificationSettingsResult,
  parseMockNotificationScenario,
  updateNotificationSetting,
} from '@/features/notification/notification-settings-client'
import {
  NOTIFICATION_LABELS,
  NOTIFICATION_TOPICS,
  type NotificationTopic,
} from '@/features/notification/notification-topics'
import { useBrowseRegion } from '@/features/onboarding/use-browse-region'
import { useSubmittedReport } from '@/features/report/use-submitted-report'
import { navHref } from '@/lib/nav'
import { useActiveRef } from '@/lib/use-active-ref'
import { useDataSource } from '@/lib/use-data-source'
import { useNavTrail } from '@/lib/use-nav-trail'
import { usePushSupport } from '@/lib/use-push-support'

import { AccountPageLayout } from './account-page-layout'
import {
  ME_NOTIFICATIONS_PATH,
  ME_PATH,
  meSearchWithPush,
  regionSearch,
  reportHrefFor,
} from './me-paths'
import { useMemberGate } from './member-gate'
import { useShownRegionName } from './member-region'
import { PushUnavailable } from './push-unavailable'
import { SwitchRow } from './settings-row'

/** 데스크톱 돌아가기. 여러 화면(내 정보 · 머리줄 알림)에서 들어와 `내 정보` 로 못 박지 않는다 */
const DESKTOP_BACK = { text: '뒤로', label: '뒤로 가기' }

/**
 * S10 알림 설정 (`/me/notifications`, #195). 회원만 본다(`useMemberGate`). **시안이 없어** 계정 화면(Settings-devices)의 틀에
 * 내 정보 알림 섹션(Settings)의 스위치 행을 옮긴다. 아래 버튼은 없다.
 *
 * - 안내 → 알림 항목(`주간 보고 요청` · `검토를 마친 동네 안내`)의 스위치. 누르면 그 항목만 바로 보낸다(확인 단계 없음, 한 번에 하나).
 *   보내는 중에는 스위치를 모두 누를 수 없고(`aria-disabled`), 실패하면 빨강 상자로 알린다(값은 그대로, 다시 누를 수 있다)
 * - **푸시 구독 · 알림 권한 요청은 하지 않는다**(2단계). 켜고 끄는 것은 알림 설정 값만 바꾼다(`notification-settings-client.ts`)
 * - **실데이터는 알림 설정 API 가 없어(BE 미정) 켜고 끌 수 없다** — 켠 것처럼 보이면 알림이 오는 줄 안다. 요청 없이 스위치를 꺼진
 *   모양(누를 수 없음)으로 두고 회색 상자로 아직 준비하고 있다고 알린다(최근 보고 내역과 같은 결)
 * - **이 기기에서 알림을 받을 수 없으면**(`usePushSupport` — `needs-install` · `unsupported`, `?mock-push=` 재현) 내 정보와 같은
 *   미지원 상자(Settings-nopush, `needs-install` 이면 설치 안내 `/install` 링크)를 두고 스위치는 꺼진 모양(누를 수 없음)이다.
 *   실데이터면 위 회색 상자만 둔다 — "홈 화면에 추가 후 알림을 켤 수 있어요" 가 아직 맞지 않다
 * - 머리줄 알림(종)은 그리지 않는다 — 이 화면으로 오는 버튼이다
 * - **뒤로**: 내 정보 · 머리줄 알림 등 여러 화면에서 들어와 앞 화면 후보 없이 앱 안 어디서 왔든 되돌린다(서비스 안내와 같다).
 *   주소로 바로 들어왔으면 내 정보(동네 · 회원 덮어쓰기 · `?mock-push=` 를 남김 — `meSearchWithPush`)로 기록을 바꿔 간다.
 *   동네 고르기에서 돌아올 때도 `?mock-push=` 가 남는다(`browse-return.ts`)
 */
export function NotificationsScreen({
  regionName,
  regionCode = null,
}: {
  /** 데스크톱 머리줄의 동네 이름(서버가 준 둘러보기 동네 · 목 예시) */
  regionName: string
  /** 둘러보기(`?region=`)로 고른 행정동 코드 */
  regionCode?: string | null
}) {
  const auth = useMemberGate({ next: ME_NOTIFICATIONS_PATH })
  if (!auth) return null
  return <Notifications auth={auth} regionName={regionName} regionCode={regionCode} />
}

function Notifications({
  auth,
  regionName,
  regionCode,
}: {
  auth: MockAuthState
  regionName: string
  regionCode: string | null
}) {
  const router = useRouter()
  const searchParams = useSearchParams()
  const { goBack } = useNavTrail()
  const active = useActiveRef()
  const source = useDataSource()
  const push = usePushSupport()
  const openBrowseRegion = useBrowseRegion(ME_NOTIFICATIONS_PATH, regionCode)
  const shownRegionName = useShownRegionName(regionName, regionCode)
  const reportLabel = reportButtonLabel(auth, useSubmittedReport() !== null)
  const scenario = parseMockNotificationScenario(searchParams.get(MOCK_NOTIFICATIONS_PARAM))
  const [load, setLoad] = useState<NotificationSettingsResult | null>(null)
  const [busy, setBusy] = useState(false)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let live = true
    // 목은 실패하지 않고 실데이터는 요청하지 않는다. 연동 때 불러오는 중 · 실패 상태를 더한다
    void getNotificationSettings(source, { scenario }).then((result) => {
      if (live) setLoad(result)
    })
    return () => {
      live = false
    }
  }, [source, scenario])

  const pushUnavailable = push === 'needs-install' || push === 'unsupported'
  const settings = load?.status === 'ready' ? load.settings : null
  // 켜고 끌 수 있는 때: 목 설정을 읽었고 이 기기가 알림을 받을 수 있다(판단 전 null 이면 아직 누를 수 없다)
  const togglable = settings !== null && push === 'supported'

  async function toggle(topic: NotificationTopic) {
    if (!togglable || busy) return
    setBusy(true)
    setFailed(false)
    try {
      const result = await updateNotificationSetting(topic, !settings[topic], source)
      if (!active.current) return
      setBusy(false)
      setLoad(result)
    } catch {
      if (!active.current) return
      setBusy(false)
      setFailed(true)
    }
  }

  return (
    <AccountPageLayout
      title="알림 설정"
      regionName={shownRegionName}
      navSearch={regionSearch(regionCode)}
      onBack={() => goBack(navHref(ME_PATH, meSearchWithPush(regionCode, searchParams)))}
      desktopBack={DESKTOP_BACK}
      onRegionClick={openBrowseRegion}
      onReportClick={() => router.push(reportHrefFor(auth, regionCode))}
      reportLabel={reportLabel}
    >
      {/* 켤 수 없을 때(실데이터 · 미지원)는 두지 않는다 — 아래 상자와 서로 다른 말을 하지 않게 */}
      {togglable && (
        <p className="text-body leading-[1.6] text-fg-sub">
          켠 알림만 보내요. 알림을 켜지 않아도 검토를 마친 동네 안내는 홈에서 볼 수 있어요.
        </p>
      )}

      {load?.status === 'unavailable' && (
        <AlertBox tone="neutral">
          알림은 아직 준비하고 있어요. 지금은 켜고 끌 수 없어요. 검토를 마친 동네 안내는 홈에서 볼
          수 있어요.
        </AlertBox>
      )}

      {settings !== null && pushUnavailable && (
        <PushUnavailable support={push} regionCode={regionCode} canEnable />
      )}

      {load !== null && (
        <div className="flex flex-col border-t border-divider">
          {NOTIFICATION_TOPICS.map((topic) => (
            <SwitchRow
              key={topic}
              title={NOTIFICATION_LABELS[topic].title}
              description={NOTIFICATION_LABELS[topic].description}
              // 켤 수 없으면 저장된 값과 무관하게 꺼진 모양이다(Settings-nopush) — 알림이 오지 않는다
              checked={togglable && settings[topic]}
              disabled={busy}
              onToggle={togglable ? () => void toggle(topic) : undefined}
            />
          ))}
        </div>
      )}

      {failed && (
        <AlertBox tone="danger">알림 설정을 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.</AlertBox>
      )}
    </AccountPageLayout>
  )
}
