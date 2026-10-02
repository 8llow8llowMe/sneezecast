'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'

import { AlertBox } from '@/components/alert-box'
import { Button } from '@/components/button'
import { ToastRegion, useToast } from '@/components/toast'
import {
  type DeviceSession,
  listSessions,
  type MockAuthState,
  revokeOtherSessions,
  revokeSession,
} from '@/features/auth/auth-client'
import { reportButtonLabel } from '@/features/home/report-gate'
import { useBrowseRegion } from '@/features/onboarding/use-browse-region'
import { useSubmittedReport } from '@/features/report/use-submitted-report'
import { formatMonthDayTime } from '@/lib/format'
import { navHref } from '@/lib/nav'
import { useActiveRef } from '@/lib/use-active-ref'

import { AccountPageLayout } from './account-page-layout'
import { ME_DEVICES_PATH, ME_PATH, meSearch, regionSearch, reportHrefFor } from './me-paths'
import { useMeTrail } from './me-trail'
import { useMemberGate } from './member-gate'
import { useShownRegionName } from './member-region'

type Load =
  | { status: 'loading' }
  /** 응답을 받지 못했다(네트워크 · 서버 오류). 다시 시도할 수 있다 */
  | { status: 'failed' }
  | { status: 'ready'; sessions: DeviceSession[] }

const OTHERS = Symbol('others')
/** 로그아웃 대상. 세션 id 이거나 이 기기를 뺀 모두(`OTHERS`)다 */
type RevokeTarget = string | typeof OTHERS

type Revoke = { target: RevokeTarget; state: 'pending' | 'failed' } | null

/** 이 기기를 맨 위에, 나머지는 최근에 쓴 순서로 둔다 */
function sortSessions(sessions: readonly DeviceSession[]): DeviceSession[] {
  const time = (session: DeviceSession) => new Date(session.lastActiveAt).getTime() || 0
  return [...sessions].sort((a, b) => Number(b.current) - Number(a.current) || time(b) - time(a))
}

/**
 * S10 로그인한 기기 (`/me/devices`, Settings-devices). 회원만 본다 — 비회원이 주소로 들어오면 로그인으로 보낸다(`useMemberGate`).
 *
 * | 상태 | 보이는 것 |
 * | --- | --- |
 * | 불러오는 중 | 안내 문단 · "기기 목록을 불러오고 있어요" |
 * | 불러오지 못함 | 빨강 상자 + `다시 시도` |
 * | 목록 | 이 기기(배지 "이 기기", "지금 사용 중") · 다른 기기(마지막 사용 시각 · `로그아웃`) · `다른 기기에서 모두 로그아웃` |
 * | 로그아웃하지 못함 | 목록 아래 빨강 상자. 목록은 그대로이고 다시 누를 수 있다 |
 *
 * - **기기 이름과 마지막 사용 시각만 보인다.** IP · 접속 지역 · 위치는 받지도 그리지도 않는다(루트 CLAUDE.md "개인정보").
 * - 이 기기는 여기서 로그아웃하지 않는다 — 내 정보의 `로그아웃` 이 한다.
 * - 시안에 확인 단계가 없어 누르면 바로 보낸다. 보내는 중에는 로그아웃 버튼이 모두 꺼진다(`aria-disabled`, 한 번에 하나).
 * - 성공하면 그 기기를 목록에서 빼고 알림(토스트)으로 알린 뒤, 누른 버튼이 사라지므로 목록으로 포커스를 옮긴다.
 *   목 서버(연동 때는 서버)에서는 화면과 무관하게 이미 끝난 일이다 — 응답 전에 화면을 떠나면 늦은 응답은 버린다.
 * - 다른 기기가 없으면 `다른 기기에서 모두 로그아웃` 을 숨긴다(시안에 없는 상태).
 */
export function DevicesScreen({
  regionName,
  regionCode = null,
}: {
  regionName: string
  regionCode?: string | null
}) {
  const auth = useMemberGate()
  if (!auth) return null
  return <Devices auth={auth} regionName={regionName} regionCode={regionCode} />
}

function Devices({
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
  const { goBack } = useMeTrail()
  const openBrowseRegion = useBrowseRegion(ME_DEVICES_PATH, regionCode)
  const shownRegionName = useShownRegionName(regionName, regionCode)
  const reportLabel = reportButtonLabel(auth, useSubmittedReport() !== null)
  const active = useActiveRef()
  const { toast, show, dismiss } = useToast()
  const [load, setLoad] = useState<Load>({ status: 'loading' })
  const [revoke, setRevoke] = useState<Revoke>(null)
  // 늦게 온 목록 응답을 버린다. 다시 시도 · 개발 모드의 effect 두 번 실행으로 요청이 겹쳐도 마지막 요청만 쓴다
  const loadSeq = useRef(0)
  const listRef = useRef<HTMLUListElement>(null)

  const fetchSessions = useCallback(() => {
    loadSeq.current += 1
    const seq = loadSeq.current
    const settle = (next: Load) => {
      if (active.current && seq === loadSeq.current) setLoad(next)
    }
    listSessions().then(
      (sessions) => settle({ status: 'ready', sessions: sortSessions(sessions) }),
      () => settle({ status: 'failed' }),
    )
  }, [active])

  useEffect(() => {
    fetchSessions()
  }, [fetchSessions])

  function retry() {
    setLoad({ status: 'loading' })
    fetchSessions()
  }

  const pending = revoke?.state === 'pending'

  async function run(target: RevokeTarget, done: string) {
    if (pending) return
    setRevoke({ target, state: 'pending' })
    try {
      await (target === OTHERS ? revokeOtherSessions() : revokeSession(target))
    } catch {
      if (active.current) setRevoke({ target, state: 'failed' })
      return
    }
    if (!active.current) return
    setRevoke(null)
    setLoad((current) =>
      current.status === 'ready'
        ? {
            ...current,
            sessions: current.sessions.filter((session) =>
              target === OTHERS ? session.current : session.id !== target,
            ),
          }
        : current,
    )
    show({ message: done })
    // 누른 버튼이 목록에서 사라진다. 포커스가 문서 처음으로 튀지 않게 목록으로 옮긴다
    listRef.current?.focus()
  }

  const backHref = navHref(ME_PATH, meSearch(regionCode, searchParams))
  const notReady = (screen: string) => show({ message: `${screen} 화면은 준비하고 있어요` })
  const others = load.status === 'ready' ? load.sessions.filter((session) => !session.current) : []

  return (
    <AccountPageLayout
      title="로그인한 기기"
      regionName={shownRegionName}
      navSearch={regionSearch(regionCode)}
      onBack={() => goBack(backHref)}
      onRegionClick={openBrowseRegion}
      onNotificationClick={() => notReady('알림 설정')}
      onReportClick={() => router.push(reportHrefFor(auth, regionCode))}
      reportLabel={reportLabel}
      footer={
        others.length > 0 ? (
          <Button
            variant="secondary"
            fullWidth
            aria-disabled={pending || undefined}
            onClick={() => void run(OTHERS, '다른 기기에서 모두 로그아웃했어요')}
          >
            다른 기기에서 모두 로그아웃
          </Button>
        ) : null
      }
    >
      <p className="text-body leading-[1.6] text-fg-sub">모르는 기기가 있으면 로그아웃해 주세요.</p>

      {/*
        목록 자리. 불러오는 중 안내 영역(role=status)은 스크린리더가 이미 있던 영역의 내용이 바뀔 때 읽으므로 늘 그려 둔다.
        비어 있어도 본문 간격(20)을 한 칸 더 차지하지 않게 목록 · 오류와 한 묶음으로 둔다
      */}
      <div className="flex flex-col">
        <div role="status">
          {load.status === 'loading' && (
            <p className="text-body text-fg-sub">기기 목록을 불러오고 있어요</p>
          )}
        </div>

        {load.status === 'failed' && (
          <AlertBox
            tone="danger"
            action={
              <Button variant="secondary" size="sm" className="self-start" onClick={retry}>
                다시 시도
              </Button>
            }
          >
            기기 목록을 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.
          </AlertBox>
        )}

        {load.status === 'ready' && (
          <ul
            ref={listRef}
            tabIndex={-1}
            aria-label="로그인한 기기 목록"
            className="flex flex-col border-t border-divider focus:outline-none"
          >
            {load.sessions.map((session) => (
              <SessionRow
                key={session.id}
                session={session}
                disabled={pending}
                onRevoke={() => void run(session.id, `${session.deviceName}에서 로그아웃했어요`)}
              />
            ))}
          </ul>
        )}
      </div>

      {revoke?.state === 'failed' && (
        <AlertBox tone="danger">로그아웃하지 못했어요. 잠시 뒤 다시 시도해 주세요.</AlertBox>
      )}

      <ToastRegion
        toast={toast}
        onAction={dismiss}
        className="fixed inset-x-0 bottom-28 px-page-mobile tablet:bottom-20 tablet:mx-auto tablet:w-dialog-tablet tablet:px-0 desktop:bottom-8"
      />
    </AccountPageLayout>
  )
}

function SessionRow({
  session,
  disabled,
  onRevoke,
}: {
  session: DeviceSession
  disabled: boolean
  onRevoke: () => void
}) {
  const lastActive = session.current ? null : formatMonthDayTime(session.lastActiveAt)
  return (
    <li className="flex min-h-17 items-center justify-between gap-3 border-b border-divider">
      <span className="flex min-w-0 flex-col gap-0.5">
        <span className="text-body font-semibold text-fg">{session.deviceName}</span>
        {session.current ? (
          <span className="text-sub text-fg-sub">지금 사용 중</span>
        ) : (
          lastActive && <span className="text-sub text-fg-sub">마지막 사용 {lastActive}</span>
        )}
      </span>
      {session.current ? (
        <span className="shrink-0 rounded-chip bg-section px-2.5 py-0.75 text-caption font-bold text-brand">
          이 기기
        </span>
      ) : (
        <button
          type="button"
          // 버튼이 여러 개라 기기 이름을 이름에 붙인다. 보이는 글자("로그아웃")를 이름 끝에 그대로 둔다
          aria-label={`${session.deviceName} 로그아웃`}
          aria-disabled={disabled || undefined}
          onClick={() => {
            if (!disabled) onRevoke()
          }}
          className="min-h-touch shrink-0 cursor-pointer rounded-button border-hairline border-inactive-bar bg-bg px-3.5 text-body-strong font-semibold text-fg aria-disabled:cursor-not-allowed aria-disabled:opacity-disabled"
        >
          로그아웃
        </button>
      )}
    </li>
  )
}
