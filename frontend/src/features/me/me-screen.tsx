'use client'

import { useEffect, useRef, useState } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'

import { AppHeader } from '@/components/app-header'
import { Button } from '@/components/button'
import { TabBar } from '@/components/tab-bar'
import { ToastRegion, useToast } from '@/components/toast'
import {
  logout,
  type MockAuthState,
  withdrawHealthConsent,
  withdrawMembership,
} from '@/features/auth/auth-client'
import { useMockAuth, useMockProfile } from '@/features/auth/use-mock-auth'
import { REPORT_GATE } from '@/features/home/report-gate'
import { REPORT_PARAM } from '@/features/report/report-flow'
import { navHref } from '@/lib/nav'
import { useActiveRef } from '@/lib/use-active-ref'
import { useHydrated } from '@/lib/use-hydrated'
import { useModalParam } from '@/lib/use-modal-param'
import { usePushSupport } from '@/lib/use-push-support'

import {
  CONFIRM_FAILURE,
  CONFIRM_KINDS,
  CONFIRM_PARAM,
  confirmAllowed,
  type ConfirmKind,
  parseConfirm,
} from './confirm'
import { ConfirmDialog } from './confirm-dialog'
import {
  ME_DEVICES_PATH,
  ME_NOTICES,
  ME_PASSWORD_PATH,
  ME_PATH,
  meSearch,
  regionSearch,
  reportHrefFor,
} from './me-paths'
import { useMeTrail } from './me-trail'
import { useRequiredStepsTarget } from './member-gate'
import { PushUnavailable } from './push-unavailable'
import { MenuRow, sectionTitleId, SettingsSection, SwitchRow } from './settings-row'

/** 대화상자별 목 API. 성공하면 `auth-client` 가 목 세션을 바꾼다(로그아웃 · 탈퇴 → guest, 동의 철회 → member-no-consent) */
const CONFIRM_ACTIONS: Record<ConfirmKind, () => Promise<void>> = {
  logout,
  'consent-withdraw': withdrawHealthConsent,
  withdraw: withdrawMembership,
}

/** 데스크톱 왼쪽 설정 메뉴 (Settings-D · Settings-guest-D). 회원 상태마다 섹션이 다르다 */
function sectionsFor(auth: MockAuthState): { id: string; label: string }[] {
  if (auth === 'guest') {
    return [
      { id: 'me-region', label: '내 동네' },
      { id: 'me-login', label: '로그인' },
      { id: 'me-privacy', label: '개인정보 안내' },
      { id: 'me-service', label: '서비스 정보' },
    ]
  }
  return [
    { id: 'me-account', label: '계정' },
    { id: 'me-region', label: '내 동네' },
    { id: 'me-notification', label: '알림' },
    // 보고는 동의한 회원만 한다. 동의하지 않은 회원에게는 내 보고 섹션이 없다
    ...(auth === 'member' ? [{ id: 'me-report', label: '내 보고' }] : []),
    { id: 'me-privacy', label: '개인정보' },
    { id: 'me-service', label: '서비스 정보' },
  ]
}

/**
 * S10 내 정보 (`/me`). 회원 상태(목, `useMockAuth`)와 로그인 방법(`useMockProfile`)으로 화면이 셋이다:
 * 이메일 회원(Settings) · 카카오 회원(Settings-kakao, 비밀번호 "설정") · 비로그인(Settings-guest).
 * 동의하지 않은 회원은 시안이 없어 회원 화면에서 `내 보고` 섹션을 빼고, `건강정보 동의 철회` 자리에 `건강정보 동의하기` 를 둔다.
 *
 * | 폭 | 구성 |
 * | --- | --- |
 * | 모바일 | 머리줄 "내 정보"(알림 없음) → 섹션 목록 → 탭바 |
 * | 태블릿 | 머리줄 "내 정보" · 알림 · 보고 버튼, 가운데 640 섹션 목록, 탭바 |
 * | 데스크톱 | 홈과 같은 머리줄, 가운데 1000 = 왼쪽 220 설정 메뉴(제목 · 섹션 바로가기) + 섹션 목록 |
 *
 * **확인 대화상자**(`?confirm=logout|consent-withdraw|withdraw`)는 메뉴 행이 기록을 쌓아 연다. 주소로 바로 들어온 값이 회원 상태와
 * 맞지 않으면(비회원의 logout, 미동의 회원의 consent-withdraw 등) 열지 않고 주소에서 지운다.
 * 보내는 중에는 닫기와 다른 확인 행을 막고, 성공하면 목 세션이 바뀐 뒤(화면과 무관) 화면이 떠 있을 때만 홈으로 기록을 바꿔 간다(replace) —
 * 뒤로 가기로 대화상자에 돌아오지 않는다. 실패하면 대화상자 안에 알리고, 보내는 중에 뒤로 가기로 대화상자를 닫았으면 알림(토스트)으로 알린다.
 * 이동하는 주소에는 `region` 만 남아 `?mock-auth=` · `?mock-provider=` 덮어쓰기가 함께 빠진다.
 *
 * 데스크톱 설정 메뉴의 바로가기는 `#id` 링크가 아니라 버튼이다. 같은 문서 `#` 링크는 Next 가 모르는 기록 항목(state 가 null)을
 * 쌓아, 그 뒤 연 대화상자의 닫기(`history.go(-1)`)가 그 항목으로 돌아가며 첫 닫기에 닫히지 않는다(docs/conventions.md).
 *
 * 로그인한 기기(`/me/devices`) · 비밀번호 변경 · 설정(`/me/password`) 행은 그 화면으로 간다. 동네(`region`)와 QA 덮어쓰기
 * (`mock-auth` · `mock-provider`)를 주소에 남긴다. 비밀번호를 바꾸거나 정하고 돌아오면 계정 화면이 내 정보 레이아웃
 * (`MeTrailProvider`)에 남긴 알림을 한 번 꺼내 토스트로 띄운다 — 회원일 때만 띄운다.
 * 아직 없는 화면(내 동네 바꾸기 · 알림 설정 · 안내 본문 등)은 홈처럼 "준비하고 있어요" 알림을 띄운다.
 */
export function MeScreen({
  regionName,
  regionCode = null,
}: {
  /** 보고 · 둘러보는 동네 이름. 목이라 홈과 같은 값이다 — 연동 때 `GET /me` 의 내 동네로 바꾼다 */
  regionName: string
  /** 둘러보기(`?region=`)로 고른 행정동 코드. 탭바 · 메뉴 · 이동 주소에 붙여 잃지 않게 한다 */
  regionCode?: string | null
}) {
  const router = useRouter()
  const searchParams = useSearchParams()
  const active = useActiveRef()
  const { toast, show, dismiss } = useToast()
  const auth = useMockAuth()
  const profile = useMockProfile()
  const confirm = useModalParam(CONFIRM_PARAM)
  // 이 기기에서 알림을 받을 수 없으면(Settings-nopush) 알림 섹션에 안내를 두고 스위치는 눌러도 아무 일이 없다
  const push = usePushSupport()
  const pushUnavailable = push === 'needs-install' || push === 'unsupported'
  // 보내는 중인 대화상자. 성공한 뒤 이동할 때까지 그대로 둔다 — 세션이 먼저 바뀌어도 대화상자가 닫히지 않게 한다
  const [pending, setPending] = useState<ConfirmKind | null>(null)
  const [failed, setFailed] = useState<ConfirmKind | null>(null)

  const navSearch = regionSearch(regionCode)
  const accountSearch = meSearch(regionCode, searchParams)
  const withRegion = (extra: Record<string, string>) =>
    new URLSearchParams({ ...(regionCode ? { region: regionCode } : {}), ...extra }).toString()

  const requested = parseConfirm(confirm.value)
  const openKind =
    requested !== null && (requested === pending || confirmAllowed(requested, auth))
      ? requested
      : null
  // 늦게 온 응답이 지금 대화상자가 열려 있는지 볼 수 있게 둔다(보내는 중에 뒤로 가기로 닫았을 수 있다)
  const openKindRef = useRef(openKind)
  useEffect(() => {
    openKindRef.current = openKind
  }, [openKind])

  // 주소로 바로 들어온 값이 회원 상태와 맞지 않으면 주소에서 지운다. 마운트 effect 에서 바로 바꾸면 Next 가 모르므로
  // 이번 그림의 effect 가 끝난 뒤로 미룬다(홈의 보고 진입 정리와 같다). 열림은 위 openKind 가 첫 그림부터 막는다
  // 레이아웃의 가드(`MeRequiredStepsGate`)가 재동의 · 동네 다시 고르기로 보낼 곳이 있으면 정리하지 않는다 — 원시 history 를 바꾸면
  // 대기 중인 그 이동을 Next 가 버린다(docs/conventions.md). 가드와 같은 판단을 읽는다
  const requiredTarget = useRequiredStepsTarget(ME_PATH)
  const mismatched = requested !== null && openKind === null && requiredTarget === null
  const { close: closeConfirmParam } = confirm
  useEffect(() => {
    if (!mismatched) return
    const timer = setTimeout(closeConfirmParam, 0)
    return () => clearTimeout(timer)
  }, [mismatched, closeConfirmParam])

  const notReady = (screen: string) => show({ message: `${screen} 화면은 준비하고 있어요` })

  // 비밀번호를 바꾸거나 정하고 왔으면 계정 화면이 내 정보 레이아웃에 남긴 알림을 한 번 꺼내 띄운다.
  // 하이드레이션 첫 그림의 회원 상태(늘 guest)로는 판단하지 않는다. 비회원이면 꺼내서 버리기만 한다
  const hydrated = useHydrated()
  const signedIn = auth !== 'guest'
  const { takeNotice } = useMeTrail()
  useEffect(() => {
    if (!hydrated) return
    const notice = takeNotice()
    if (notice && signedIn) show({ message: ME_NOTICES[notice] })
  }, [hydrated, signedIn, takeNotice, show])

  function openConfirm(kind: ConfirmKind) {
    setFailed(null)
    confirm.open(kind)
  }

  function closeConfirm() {
    if (pending) return
    setFailed(null)
    confirm.close()
  }

  async function runConfirm(kind: ConfirmKind) {
    if (pending) return
    setPending(kind)
    setFailed(null)
    try {
      await CONFIRM_ACTIONS[kind]()
    } catch {
      if (!active.current) return
      setPending(null)
      // 대화상자가 열려 있으면 그 안에, 이미 닫았으면(뒤로 가기) 내 정보 알림으로 같은 문구를 띄운다
      if (openKindRef.current === kind) setFailed(kind)
      else show({ message: CONFIRM_FAILURE[kind] })
      return
    }
    if (!active.current) return
    // 로그아웃 · 탈퇴는 비회원 홈, 동의 철회는 홈으로 간다.
    // 동의 철회는 원래 "동의 철회 직후 홈"(Home-purging, 다음 이슈)이다 — 그 화면이 생기면 이동할 곳을 바꾼다
    router.replace(navHref('/', navSearch))
  }

  // 머리줄 보고 버튼: 비회원은 로그인, 회원은 홈의 보고 진입(미동의면 동의 시트, 동의했으면 보고 흐름)
  const reportHref = reportHrefFor(auth, regionCode)

  const sections = sectionsFor(auth)

  // 설정 메뉴 바로가기. 기록을 쌓지 않고 섹션으로 옮긴 뒤 그 제목에 포커스를 준다(키보드 · 스크린리더가 같은 자리에서 이어 읽는다)
  function jumpTo(sectionId: string) {
    document.getElementById(sectionId)?.scrollIntoView({ block: 'start' })
    document.getElementById(sectionTitleId(sectionId))?.focus({ preventScroll: true })
  }

  return (
    <div className="flex min-h-dvh flex-col">
      <AppHeader
        title="내 정보"
        regionName={regionName}
        current="me"
        onRegionClick={() => notReady('동네 바꾸기')}
        onNotificationClick={() => notReady('알림 설정')}
        onReportClick={() => router.push(reportHref)}
        reportLabel={auth === 'guest' ? '로그인하고 보고하기' : undefined}
        navSearch={navSearch}
      />

      <div className="flex grow justify-center px-page-mobile pt-2 pb-6 tablet:px-10 tablet:py-7 desktop:p-8">
        <div className="flex w-full max-w-160 desktop:max-w-250 desktop:gap-12">
          <div className="hidden w-55 shrink-0 flex-col desktop:flex">
            <h1 className="mb-3 ml-3 text-sheet-title font-bold text-fg">내 정보</h1>
            <nav aria-label="설정 메뉴" className="flex flex-col gap-1">
              {sections.map((section) => (
                <button
                  key={section.id}
                  type="button"
                  onClick={() => jumpTo(section.id)}
                  className="flex h-11 cursor-pointer items-center rounded-small px-3 text-left text-body font-medium text-fg-sub"
                >
                  {section.label}
                </button>
              ))}
            </nav>
          </div>

          <main className="flex min-w-0 grow flex-col gap-7">
            {auth === 'guest' ? (
              <GuestSections
                regionName={regionName}
                onLogin={() => router.push('/login')}
                notReady={notReady}
              />
            ) : (
              <>
                <SettingsSection id="me-account" title="계정">
                  <MenuRow
                    title="닉네임"
                    value={profile?.nickname}
                    onClick={() => notReady('닉네임 바꾸기')}
                  />
                  <MenuRow
                    title="로그인 방법"
                    value={
                      profile
                        ? `${profile.provider === 'kakao' ? '카카오' : '이메일'} · ${profile.email}`
                        : undefined
                    }
                  />
                  {/* 아직 비밀번호가 없는 카카오 회원은 설정, 그 밖에는 변경이다. 같은 화면(`/me/password`)이 둘을 맡는다 */}
                  {profile?.hasPassword === false ? (
                    <MenuRow
                      title="비밀번호 설정"
                      description="이메일로도 로그인할 수 있어요"
                      href={navHref(ME_PASSWORD_PATH, accountSearch)}
                    />
                  ) : (
                    <MenuRow
                      title="비밀번호 변경"
                      href={navHref(ME_PASSWORD_PATH, accountSearch)}
                    />
                  )}
                  <MenuRow title="로그인한 기기" href={navHref(ME_DEVICES_PATH, accountSearch)} />
                </SettingsSection>

                <SettingsSection id="me-region" title="내 동네">
                  <MenuRow
                    title="보고 동네"
                    value={regionName}
                    onClick={() => notReady('내 동네 바꾸기')}
                  />
                  <MenuRow title="관심 동네" onClick={() => notReady('관심 동네')} />
                </SettingsSection>

                <SettingsSection id="me-notification" title="알림">
                  <PushUnavailable support={push} regionCode={regionCode} />
                  <SwitchRow
                    title="주간 보고 요청"
                    description="월요일 아침"
                    onClick={pushUnavailable ? undefined : () => notReady('알림 설정')}
                  />
                  <SwitchRow
                    title="검토를 마친 동네 안내"
                    description="운영자가 발행했을 때"
                    onClick={pushUnavailable ? undefined : () => notReady('알림 설정')}
                  />
                </SettingsSection>

                {auth === 'member' && (
                  <SettingsSection id="me-report" title="내 보고">
                    <MenuRow title="이번 주 보고 수정" onClick={() => notReady('보고 수정')} />
                    <MenuRow
                      title="최근 보고 내역"
                      value="52주 보관"
                      onClick={() => notReady('최근 보고 내역')}
                    />
                  </SettingsSection>
                )}

                <SettingsSection id="me-privacy" title="개인정보">
                  <MenuRow
                    title="모으는 정보와 보관 기간"
                    value="52주"
                    onClick={() => notReady('모으는 정보와 보관 기간')}
                  />
                  {auth === 'member' ? (
                    <MenuRow
                      title={<span className="text-danger">건강정보 동의 철회</span>}
                      description="보낸 보고를 모두 지워요"
                      chevron={false}
                      disabled={pending !== null}
                      onClick={() => openConfirm('consent-withdraw')}
                    />
                  ) : (
                    <MenuRow
                      title="건강정보 동의하기"
                      description="동의하면 이번 주 보고를 할 수 있어요"
                      href={`/?${withRegion({ [REPORT_PARAM]: REPORT_GATE.healthConsent })}`}
                    />
                  )}
                </SettingsSection>

                <ServiceSection notReady={notReady} />

                <SettingsSection label="로그아웃 · 회원 탈퇴">
                  <MenuRow
                    title="로그아웃"
                    chevron={false}
                    disabled={pending !== null}
                    onClick={() => openConfirm('logout')}
                  />
                  <MenuRow
                    title={<span className="text-fg-muted">회원 탈퇴</span>}
                    chevron={false}
                    disabled={pending !== null}
                    onClick={() => openConfirm('withdraw')}
                  />
                </SettingsSection>
              </>
            )}
          </main>
        </div>
      </div>

      <div className="sticky bottom-0">
        <TabBar current="me" navSearch={navSearch} />
      </div>

      {CONFIRM_KINDS.map((kind) => (
        <ConfirmDialog
          key={kind}
          kind={kind}
          open={openKind === kind}
          pending={pending === kind}
          failed={failed === kind}
          onConfirm={() => void runConfirm(kind)}
          onClose={closeConfirm}
        />
      ))}

      <ToastRegion
        toast={toast}
        onAction={dismiss}
        className="fixed inset-x-0 bottom-20 px-page-mobile tablet:mx-auto tablet:w-dialog-tablet tablet:px-0 desktop:bottom-8"
      />
    </div>
  )
}

/** 비로그인 (Settings-guest). 둘러보는 동네 · 로그인 안내 · 개인정보 안내 · 서비스 정보 */
function GuestSections({
  regionName,
  onLogin,
  notReady,
}: {
  regionName: string
  onLogin: () => void
  notReady: (screen: string) => void
}) {
  return (
    <>
      <SettingsSection id="me-region" title="내 동네">
        {/* 둘러보기 동네 고르기(S02-1 Setup-1-browse)는 이미 있다 */}
        <MenuRow title="둘러보는 동네" value={regionName} href="/browse/region" />
      </SettingsSection>

      <section
        id="me-login"
        aria-labelledby="me-login-title"
        className="flex scroll-mt-8 flex-col gap-3 rounded-card bg-section p-5"
      >
        <h2
          id="me-login-title"
          tabIndex={-1}
          className="text-section-title font-bold text-fg focus:outline-none"
        >
          로그인하면 보고할 수 있어요
        </h2>
        <p className="text-body-strong leading-[1.55] text-fg-sub">
          한 주에 한 번 10초면 돼요. 이름·연락처·주소는 받지 않아요.
        </p>
        <Button fullWidth onClick={onLogin}>
          로그인하고 보고하기
        </Button>
      </section>

      <SettingsSection id="me-privacy" title="개인정보 안내">
        <MenuRow
          title="모으는 정보와 보관 기간"
          value="52주"
          onClick={() => notReady('모으는 정보와 보관 기간')}
        />
        <MenuRow title="개인정보 처리방침" onClick={() => notReady('개인정보 처리방침')} />
      </SettingsSection>

      <ServiceSection notReady={notReady} />
    </>
  )
}

function ServiceSection({ notReady }: { notReady: (screen: string) => void }) {
  return (
    <SettingsSection id="me-service" title="서비스 정보">
      <MenuRow
        title="데이터 출처"
        value="질병관리청 · SGIS"
        onClick={() => notReady('데이터 출처')}
      />
      <MenuRow
        title="AI 사용 방식"
        value="안내문 초안만"
        onClick={() => notReady('AI 사용 방식')}
      />
    </SettingsSection>
  )
}
