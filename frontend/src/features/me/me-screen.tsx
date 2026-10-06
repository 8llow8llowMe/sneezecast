'use client'

import { useEffect, useRef, useState } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'

import { AlertBox } from '@/components/alert-box'
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
import { type LoadStatus, retryMemberInfo } from '@/features/auth/member-info'
import { useMockAuth, useMockProfile, useMockProfileStatus } from '@/features/auth/use-mock-auth'
import { REPORT_GATE, reportButtonLabel } from '@/features/home/report-gate'
import { useBrowseRegion } from '@/features/onboarding/use-browse-region'
import { REPORT_PARAM } from '@/features/report/types'
import { useSubmittedReport } from '@/features/report/use-submitted-report'
import type { DataSource } from '@/lib/data-source'
import { navHref } from '@/lib/nav'
import { useActiveRef } from '@/lib/use-active-ref'
import { useDataSource } from '@/lib/use-data-source'
import { useHydrated } from '@/lib/use-hydrated'
import { useModalParam } from '@/lib/use-modal-param'
import { useNavTrail } from '@/lib/use-nav-trail'
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
import { INFO_PATHS } from './info-pages'
import { CONSENT_WITHDRAWN_NOTICE, leaveHomeNotice } from './leave-notice'
import {
  ME_DEVICES_PATH,
  ME_NICKNAME_PATH,
  ME_NOTICES,
  ME_PASSWORD_PATH,
  ME_PATH,
  ME_REGION_PATH,
  meSearch,
  regionSearch,
  reportHrefFor,
} from './me-paths'
import { useMeTrail } from './me-trail'
import { useMemberGate, useRequiredStepsTarget } from './member-gate'
import { useMemberRegion, useMemberRegionStatus, useShownRegionName } from './member-region'
import { PushUnavailable } from './push-unavailable'
import { MenuRow, sectionTitleId, SettingsSection, SwitchRow } from './settings-row'

/**
 * 대화상자별 API. 데이터 출처를 넘긴다 — 로그아웃은 실데이터면 세션 저장소, 목이면 목 세션을 비운다. 동의 철회 · 탈퇴는 아직 목이라
 * 출처를 쓰지 않는다. 성공하면 `auth-client` 가 세션을 바꾼다(로그아웃 · 탈퇴 · 동의 철회 → guest)
 */
const CONFIRM_ACTIONS: Record<ConfirmKind, (source: DataSource) => Promise<void>> = {
  logout,
  'consent-withdraw': withdrawHealthConsent,
  withdraw: withdrawMembership,
}

/** 데스크톱 왼쪽 설정 메뉴 (Settings-D). 동의 여부마다 섹션이 다르다 */
function sectionsFor(auth: Exclude<MockAuthState, 'guest'>): { id: string; label: string }[] {
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
 * S10 내 정보 (`/me`). **회원만 본다** — 비회원이면 본문을 그리지 않고 로그인(`/login?next=/me`, 둘러보기 동네 유지)으로 기록을 바꿔
 * 간다(`useMemberGate`, #123). 탭바 · 머리줄의 `내 정보` 링크는 회원 상태와 무관하게 `/me` 이고 이 가드가 비회원을 보낸다.
 * 로그인 방법(`useMockProfile`)으로 화면이 둘이다: 이메일 회원(Settings) · 카카오 회원(Settings-kakao). 비밀번호가 없는 회원
 * (`hasPassword` false — 카카오로만 로그인)에게는 시안의 `비밀번호 설정` 행을 그리지 않는다(설정 API 없음, #166).
 * 비로그인 화면(Settings-guest)은 #123 에서 그리지 않게 됐다.
 * 동의하지 않은 회원은 시안이 없어 회원 화면에서 `내 보고` 섹션을 빼고, `건강정보 동의 철회` 자리에 `건강정보 동의하기` 를 둔다.
 *
 * 머리줄 · 탭바(셸)는 판단 전(서버 · 하이드레이션 첫 그림)에도 그린다. 본문 · 설정 메뉴만 회원으로 판단된 뒤 그린다.
 * 로그아웃 · 탈퇴 · 동의 철회에 성공하면 세션이 먼저 비회원이 되지만, 보내는 중(`pending`)에는 가드를 멈춰 로그인이 아니라 홈으로 간다.
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
 * 닉네임(`/me/nickname`) · 로그인한 기기(`/me/devices`) · 비밀번호 변경(`/me/password`) · 보고 동네(`/me/region`) 행은 그 화면으로 간다.
 * 동네(`region`)와 QA 덮어쓰기(`mock-auth` · `mock-provider`)를 주소에 남긴다. 비밀번호를 바꾸고 돌아오면 계정 화면이 내 정보 레이아웃
 * (`MeTrailProvider`)에 남긴 알림을 한 번 꺼내 토스트로 띄운다 — 회원일 때만 띄운다(내 동네 · 닉네임을 바꾸고 와도 같다).
 * 아직 없는 화면(관심 동네 · 알림 설정 · 안내 본문 등)은 홈처럼 "준비하고 있어요" 알림을 띄운다.
 *
 * **보고 동네 행은 늘 회원의 내 동네**다(#141). 머리줄 동네 이름은 둘러보기 동네(`?region=`)가 있으면 그 동네, 없으면 내 동네이고
 * 누르면 둘러볼 동네 고르기(`/browse/region?next=/me`)로 간다 — 내 동네는 바꾸지 않는다. 내 동네를 모르면 행의 값을 비운다.
 *
 * **실데이터는 프로필(`GET /api/v1/members/me`)과 내 동네(`GET /api/v1/members/me/region`)를 따로 읽는다**(#164). 읽는 동안은
 * 계정 섹션 위에 "내 정보를 불러오고 있어요", 하나라도 읽지 못하면 빨강 상자와 `다시 시도`(실패한 쪽만 다시 읽음)를 두고, 모르는
 * 값(닉네임 · 로그인 방법 · 보고 동네)은 비운다 — 예시 값을 채우지 않는다. 목데이터는 늘 읽은 상태다.
 *
 * **안내 행**(`모으는 정보와 보관 기간` · `데이터 출처` · `AI 사용 방식`)은 서비스 안내 화면(#193, `info-screen.tsx`)으로 간다.
 * 계정 화면 행처럼 동네 · 덮어쓰기 쿼리를 남긴다. 안내 화면은 비회원도 볼 수 있다.
 */
export function MeScreen({
  regionName,
  regionCode = null,
}: {
  /** 머리줄 동네 이름(서버가 준 둘러보기 동네 · 목 예시). 둘러보기 동네가 없으면 회원의 내 동네로 덮는다(`useShownRegionName`) */
  regionName: string
  /** 둘러보기(`?region=`)로 고른 행정동 코드. 탭바 · 메뉴 · 이동 주소에 붙여 잃지 않게 한다 */
  regionCode?: string | null
}) {
  const router = useRouter()
  const navTrail = useNavTrail()
  const searchParams = useSearchParams()
  const active = useActiveRef()
  const source = useDataSource()
  const { toast, show, dismiss } = useToast()
  const auth = useMockAuth()
  const profile = useMockProfile()
  // 이번 주에 보낸 보고(목). 머리줄 보고 버튼 글자와 `내 보고` 의 첫 항목이 따른다. 하이드레이션 첫 그림은 없음이다
  const submitted = useSubmittedReport() !== null
  const reportLabel = reportButtonLabel(auth, submitted)
  const confirm = useModalParam(CONFIRM_PARAM)
  // 이 기기에서 알림을 받을 수 없으면(Settings-nopush) 알림 섹션에 안내를 두고 스위치는 눌러도 아무 일이 없다
  const push = usePushSupport()
  const pushUnavailable = push === 'needs-install' || push === 'unsupported'
  // 보내는 중인 대화상자. 성공한 뒤 이동할 때까지 그대로 둔다 — 세션이 먼저 바뀌어도 대화상자가 닫히지 않게 한다
  const [pending, setPending] = useState<ConfirmKind | null>(null)
  const [failed, setFailed] = useState<ConfirmKind | null>(null)
  // 비회원이면 로그인으로 보낸다(돌아올 곳 /me). 보내는 중(로그아웃 · 탈퇴 · 동의 철회 성공 뒤 홈으로 가는 중)에는 멈춘다
  const member = useMemberGate({ next: ME_PATH, paused: pending !== null })
  const memberRegion = useMemberRegion()
  const infoLoad = combineLoad(useMockProfileStatus(), useMemberRegionStatus())
  const shownRegionName = useShownRegionName(regionName, regionCode)
  const openBrowseRegion = useBrowseRegion(ME_PATH, regionCode)

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
  // 비회원은 가드가 로그인으로 보내므로 정리하지 않는다(정리가 그 이동을 버리게 한다)
  const mismatched =
    requested !== null && openKind === null && requiredTarget === null && member !== null
  const { close: closeConfirmParam } = confirm
  useEffect(() => {
    if (!mismatched) return
    const timer = setTimeout(closeConfirmParam, 0)
    return () => clearTimeout(timer)
  }, [mismatched, closeConfirmParam])

  const notReady = (screen: string) => show({ message: `${screen} 화면은 준비하고 있어요` })

  // 비밀번호 · 내 동네를 바꾸고 왔으면 계정 화면이 내 정보 레이아웃에 남긴 알림을 한 번 꺼내 띄운다.
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
      await CONFIRM_ACTIONS[kind](source)
    } catch {
      if (!active.current) return
      setPending(null)
      // 대화상자가 열려 있으면 그 안에, 이미 닫았으면(뒤로 가기) 내 정보 알림으로 같은 문구를 띄운다
      if (openKindRef.current === kind) setFailed(kind)
      else show({ message: CONFIRM_FAILURE[kind] })
      return
    }
    if (!active.current) return
    // 셋 모두 비회원 홈으로 간다. 동의 철회는 로그아웃이 따라오므로 홈이 알림으로 알린다.
    // 원래 시안은 "동의 철회 직후 홈"(Home-purging, 다음 이슈)이다 — 그 화면이 생기면 이동할 곳을 바꾼다
    if (kind === 'consent-withdraw') leaveHomeNotice(CONSENT_WITHDRAWN_NOTICE)
    navTrail.replace(navHref('/', navSearch))
  }

  // 머리줄 보고 버튼: 비회원은 로그인, 회원은 홈의 보고 진입(미동의면 동의 시트, 동의했으면 보고 흐름)
  const reportHref = reportHrefFor(auth, regionCode)

  const sections = member ? sectionsFor(member) : []

  // 설정 메뉴 바로가기. 기록을 쌓지 않고 섹션으로 옮긴 뒤 그 제목에 포커스를 준다(키보드 · 스크린리더가 같은 자리에서 이어 읽는다)
  function jumpTo(sectionId: string) {
    document.getElementById(sectionId)?.scrollIntoView({ block: 'start' })
    document.getElementById(sectionTitleId(sectionId))?.focus({ preventScroll: true })
  }

  return (
    <div className="flex min-h-dvh flex-col">
      {/* 비회원(판단 전 · 로그인으로 가는 중)에게는 알림(종)을 그리지 않는다 (#123) */}
      <AppHeader
        title="내 정보"
        regionName={shownRegionName}
        current="me"
        onRegionClick={openBrowseRegion}
        onNotificationClick={auth === 'guest' ? undefined : () => notReady('알림 설정')}
        onReportClick={() => router.push(reportHref)}
        reportLabel={reportLabel}
        navSearch={navSearch}
      />

      <div className="flex grow justify-center px-page-mobile pt-2 pb-6 tablet:px-10 tablet:py-7 desktop:p-8">
        <div className="flex w-full max-w-160 desktop:max-w-250 desktop:gap-12">
          <div className="hidden w-55 shrink-0 flex-col desktop:flex">
            <h1 className="mb-3 ml-3 text-sheet-title font-bold text-fg">내 정보</h1>
            {member && (
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
            )}
          </div>

          <main className="flex min-w-0 grow flex-col gap-7">
            {/* 비회원 · 판단 전이면 본문이 없다. 로그아웃 · 탈퇴 뒤 홈으로 가는 동안도 비어 있고 대화상자만 열린 채 꺼져 있다 */}
            {member && (
              <>
                <SettingsSection id="me-account" title="계정">
                  <InfoLoadNotice status={infoLoad} />
                  <MenuRow
                    title="닉네임"
                    value={profile?.nickname}
                    href={navHref(ME_NICKNAME_PATH, accountSearch)}
                  />
                  <MenuRow
                    title="로그인 방법"
                    value={
                      profile
                        ? `${profile.provider === 'kakao' ? '카카오' : '이메일'} · ${profile.email}`
                        : undefined
                    }
                  />
                  {/*
                    비밀번호가 있는 회원에게만 보인다. 카카오로만 로그인하는 회원(`hasPassword` false)은 비밀번호가 없고 설정 API 도
                    없다(백엔드 #61, #166). 실데이터에서 내 정보를 읽기 전 · 읽지 못함이면 있는지 몰라 보이지 않는다
                  */}
                  {profile?.hasPassword === true && (
                    <MenuRow
                      title="비밀번호 변경"
                      href={navHref(ME_PASSWORD_PATH, accountSearch)}
                    />
                  )}
                  <MenuRow title="로그인한 기기" href={navHref(ME_DEVICES_PATH, accountSearch)} />
                </SettingsSection>

                <SettingsSection id="me-region" title="내 동네">
                  {/* 둘러보는 동네가 아니라 회원의 내 동네다. 모르면(가입 없이 이메일 로그인) 값을 비운다 */}
                  <MenuRow
                    title="보고 동네"
                    value={memberRegion?.name}
                    href={navHref(ME_REGION_PATH, accountSearch)}
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

                {member === 'member' && (
                  <SettingsSection id="me-report" title="내 보고">
                    {/* 보낸 뒤면 수정(Settings 시안), 보내기 전이면 보고하기(시안 없음). 둘 다 홈의 보고 진입으로 간다 —
                        보낸 뒤면 보고 흐름이 수정으로 열린다 */}
                    <MenuRow
                      title={submitted ? '이번 주 보고 수정' : '이번 주 보고하기'}
                      href={reportHref}
                    />
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
                    href={navHref(INFO_PATHS.privacy, accountSearch)}
                  />
                  {member === 'member' ? (
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

                <ServiceSection search={accountSearch} />

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

/** 프로필 · 내 동네 읽기를 하나로. 하나라도 읽지 못했으면 `failed`, 읽는 중이 있으면 `loading` 이다 */
function combineLoad(profile: LoadStatus, region: LoadStatus): LoadStatus {
  if (profile === 'failed' || region === 'failed') return 'failed'
  if (profile === 'loading' || region === 'loading') return 'loading'
  return 'ready'
}

/**
 * 내 정보를 읽는 중 · 읽지 못함 안내(로그인한 기기 목록과 같은 모양). 읽는 중 안내 영역(role=status)은 스크린리더가 이미 있던 영역의
 * 내용이 바뀔 때 읽으므로 늘 그려 둔다 — 비어 있으면 높이가 없다
 */
function InfoLoadNotice({ status }: { status: LoadStatus }) {
  return (
    <>
      <div role="status">
        {status === 'loading' && (
          <p className="py-2 text-body text-fg-sub">내 정보를 불러오고 있어요</p>
        )}
      </div>
      {status === 'failed' && (
        <AlertBox
          tone="danger"
          className="my-2"
          action={
            <Button variant="secondary" size="sm" className="self-start" onClick={retryMemberInfo}>
              다시 시도
            </Button>
          }
        >
          내 정보를 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.
        </AlertBox>
      )}
    </>
  )
}

/** 서비스 정보 섹션. 행은 안내 화면(#193)으로 간다 — 동네 · 덮어쓰기 쿼리(`search`)를 계정 화면처럼 남긴다 */
function ServiceSection({ search }: { search: string }) {
  return (
    <SettingsSection id="me-service" title="서비스 정보">
      <MenuRow
        title="데이터 출처"
        value="질병관리청 · SGIS"
        href={navHref(INFO_PATHS['data-sources'], search)}
      />
      <MenuRow title="AI 사용 방식" value="안내문 초안만" href={navHref(INFO_PATHS.ai, search)} />
    </SettingsSection>
  )
}
