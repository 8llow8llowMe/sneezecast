'use client'

import { useEffect, useState } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'

import { AlertBox } from '@/components/alert-box'
import { Button } from '@/components/button'
import type { MockAuthState } from '@/features/auth/auth-client'
import { reportButtonLabel } from '@/features/home/report-gate'
import {
  getNotificationSettings,
  MOCK_NOTIFICATIONS_PARAM,
  type NotificationSettings,
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

/** 설정 읽기. null 은 읽는 중이다 */
type Load = { status: 'ready'; settings: NotificationSettings } | { status: 'failed' } | null

/** 데스크톱 돌아가기. 여러 화면(내 정보 · 머리줄 알림)에서 들어와 `내 정보` 로 못 박지 않는다 */
const DESKTOP_BACK = { text: '뒤로', label: '뒤로 가기' }

/**
 * S10 알림 설정 (`/me/notifications`, #195). 회원만 본다(`useMemberGate`). **시안이 없어** 계정 화면(Settings-devices)의 틀에
 * 내 정보 알림 섹션(Settings)의 스위치 행을 옮긴다. 아래 버튼은 없다.
 *
 * - 안내 → 알림 항목(`주간 보고 요청` · `검토를 마친 동네 안내`)의 스위치. 누르면 그 항목만 바로 보낸다(확인 단계 없음, 한 번에 하나).
 *   보내는 중에는 스위치를 모두 누를 수 없고(`aria-disabled`), 실패하면 빨강 상자로 알린다(값은 그대로, 다시 누를 수 있다)
 * - 설정은 실데이터면 알림 설정 API(#218 · 연동 #250)에서 읽고 저장한다. 읽는 중에는 안내 문장, 읽지 못하면 빨강 상자와 `다시 시도` 다
 * - **푸시 구독 · 알림 권한 요청은 하지 않는다**(2단계). 켜고 끄는 것은 알림 설정 값만 바꾼다(`notification-settings-client.ts`).
 *   그래서 켤 수 있을 때 회색 상자로 아직 알림을 보내지 않는다고 알린다 — 켠 모양만 보면 알림이 오는 줄 안다
 * - **이 기기에서 알림을 받을 수 없으면**(`usePushSupport` — `needs-install` · `unsupported`, `?mock-push=` 재현) 내 정보와 같은
 *   미지원 상자(Settings-nopush, `needs-install` 이면 설치 안내 `/install` 링크)를 두고 스위치는 꺼진 모양(누를 수 없음)이다
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
  const [load, setLoad] = useState<Load>(null)
  const [busy, setBusy] = useState(false)
  const [failed, setFailed] = useState(false)
  // 다시 시도할 때 올린다(설정을 다시 읽는다)
  const [loadKey, setLoadKey] = useState(0)

  useEffect(() => {
    let live = true
    // 늦게 온 응답(화면을 떠남 · 출처나 재현이 바뀜 · 다시 시도)은 버린다. 목은 실패하지 않는다
    getNotificationSettings(source, { scenario }).then(
      (settings) => {
        if (live) setLoad({ status: 'ready', settings })
      },
      () => {
        if (live) setLoad({ status: 'failed' })
      },
    )
    return () => {
      live = false
    }
  }, [source, scenario, loadKey])

  function retryLoad() {
    setLoad(null)
    setLoadKey((key) => key + 1)
  }

  const pushUnavailable = push === 'needs-install' || push === 'unsupported'
  const settings = load?.status === 'ready' ? load.settings : null
  // 켜고 끌 수 있는 때: 설정을 읽었고 이 기기가 알림을 받을 수 있다(판단 전 null 이면 아직 누를 수 없다)
  const togglable = settings !== null && push === 'supported'

  async function toggle(topic: NotificationTopic) {
    if (!togglable || busy) return
    setBusy(true)
    setFailed(false)
    try {
      const next = await updateNotificationSetting(topic, !settings[topic], source)
      if (!active.current) return
      setBusy(false)
      setLoad({ status: 'ready', settings: next })
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
      {/* 켤 수 없을 때(미지원 · 읽지 못함)는 두지 않는다 — 아래 상자와 서로 다른 말을 하지 않게 */}
      {togglable && (
        <>
          <p className="text-body leading-[1.6] text-fg-sub">
            켠 알림만 보내요. 알림을 켜지 않아도 검토를 마친 동네 안내는 홈에서 볼 수 있어요.
          </p>
          {/* 푸시 발송은 2단계다. 켠 모양만 보면 알림이 오는 줄 안다 */}
          <AlertBox tone="neutral">
            알림은 아직 보내지 않아요. 고른 값은 저장해 두고, 알림을 보내기 시작하면 다시 알려
            드려요.
          </AlertBox>
        </>
      )}

      {load?.status === 'failed' && (
        <AlertBox
          tone="danger"
          action={
            <Button variant="secondary" size="sm" className="self-start" onClick={retryLoad}>
              다시 시도
            </Button>
          }
        >
          알림 설정을 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.
        </AlertBox>
      )}

      {settings !== null && pushUnavailable && (
        <PushUnavailable support={push} regionCode={regionCode} />
      )}

      {/*
        불러오는 중 안내 영역(role=status)은 스크린리더가 이미 있던 영역의 내용이 바뀔 때 읽으므로 늘 그려 둔다.
        스위치 목록과 한 묶음에 두어 비어 있을 때 화면 간격(gap)이 늘지 않게 한다
      */}
      <div className="flex flex-col">
        <div role="status">
          {load === null && <p className="text-body text-fg-sub">알림 설정을 불러오고 있어요</p>}
        </div>

        {settings !== null && (
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
      </div>

      {failed && (
        <AlertBox tone="danger">알림 설정을 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.</AlertBox>
      )}
    </AccountPageLayout>
  )
}
