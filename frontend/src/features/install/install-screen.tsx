'use client'

import { useEffect, useId, useState } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'

import clsx from 'clsx'

import { Button } from '@/components/button'
import { IconButton } from '@/components/icon-button'
import { CloseIcon } from '@/components/icons'
import { ToastRegion, useToast } from '@/components/toast'
import { HOME_PATH } from '@/features/onboarding/paths'
import { navHref } from '@/lib/nav'
import { useDevicePlatform } from '@/lib/use-push-support'

import { clearInstallEntry, enteredInstallInApp, installSearch } from './install-entry'
import {
  guideFor,
  INSTALL_GUIDES,
  type InstallGuideKey,
  MOBILE_GUIDE_KEYS,
  WIDE_GUIDE_KEYS,
} from './install-guides'

/** 묶음이 보이는 폭. 기기를 모르면 모바일 · 넓은 화면 묶음을 따로 그리고 폭으로 하나만 보인다 */
type GuideVisibility = 'always' | 'mobile' | 'wide'

const VISIBILITY_CLASS: Record<GuideVisibility, string> = {
  always: 'flex',
  mobile: 'flex tablet:hidden',
  wide: 'hidden tablet:flex',
}

/** 방법 묶음 하나. 제목 15 굵게 회색 · 단계마다 번호 원(28) + 문장 */
function GuideSection({
  guideKey,
  visibility,
}: {
  guideKey: InstallGuideKey
  visibility: GuideVisibility
}) {
  const guide = INSTALL_GUIDES[guideKey]
  const titleId = useId()
  return (
    <section aria-labelledby={titleId} className={clsx('flex-col', VISIBILITY_CLASS[visibility])}>
      <h2 id={titleId} className="mb-1 text-body font-bold text-fg-sub">
        {guide.title}
      </h2>
      <ol className="flex flex-col">
        {guide.steps.map((step, index) => (
          <li key={step} className="flex items-start gap-3 py-2">
            <span
              aria-hidden="true"
              className="flex size-7 shrink-0 items-center justify-center rounded-chip bg-section text-body-strong font-bold text-brand"
            >
              {index + 1}
            </span>
            <span className="pt-0.75 text-body leading-normal text-fg">{step}</span>
          </li>
        ))}
      </ol>
    </section>
  )
}

/**
 * S12 홈 화면 추가 안내 (`/install`, 시안 Install · Install-T · Install-D).
 *
 * | 폭 | 구성 |
 * | --- | --- |
 * | 모바일 | 전체 화면. 머리줄 오른쪽 닫기 → 제목 두 줄 · 설명 두 문장 → 방법 묶음 → 아래 고정 `알림 켜기` · `나중에 할게요`(회색 글자) |
 * | 태블릿 · 데스크톱 | 회색 바탕 가운데 패널(520 · 480). 제목 한 줄 · 설명 둘째 문장만 → 방법 묶음 → 버튼 한 줄(`나중에 할게요` · `알림 켜기`) |
 *
 * 한 DOM 으로 폭마다 모양만 바꾼다. 시안의 태블릿 · 데스크톱은 홈 위 대화상자지만 단독 주소라 뒤에 홈을 그리지 않는다.
 *
 * 방법 묶음은 하이드레이션 뒤 기기(UA)로 하나만 보인다. 판별 전(서버 · 첫 그림)과 모르는 기기는 폭에 맞는 두 묶음을 다 보인다.
 * **알림 권한을 묻지 않는다** — `알림 켜기` 는 푸시 구독(2단계)이 생길 때까지 "준비하고 있어요" 알림만 띄운다.
 *
 * 닫기 · `나중에 할게요`: 앱 안 링크(내 정보)로 왔으면 `router.back()`, 주소로 바로 들어왔으면 홈으로 기록을 바꿔 간다
 * (동네 · 덮어쓰기는 남긴다). 판별은 진입 링크가 남긴 표시를 마운트 때 소비해서 한다(`install-entry.ts`).
 */
export function InstallScreen({ regionCode = null }: { regionCode?: string | null }) {
  const router = useRouter()
  const searchParams = useSearchParams()
  const guide = guideFor(useDevicePlatform())
  const { toast, show, dismiss } = useToast()
  // 앱 안 링크가 남긴 표시를 마운트 때 한 번 읽고 비운다. 비우는 일은 effect 에서 해 StrictMode 의 두 번 그리기에도 값이 같다
  const [inApp] = useState(enteredInstallInApp)
  useEffect(() => {
    clearInstallEntry()
  }, [])

  function close() {
    if (inApp) router.back()
    else router.replace(navHref(HOME_PATH, installSearch(regionCode, searchParams)))
  }

  return (
    <div className="flex min-h-dvh flex-col bg-bg tablet:items-center tablet:justify-center tablet:bg-section tablet:p-6">
      <div className="flex w-full grow flex-col tablet:w-dialog-tablet tablet:max-w-full tablet:grow-0 tablet:gap-3.5 tablet:rounded-dialog tablet:bg-bg tablet:px-7 tablet:pt-4 tablet:pb-7 desktop:w-dialog-desktop">
        <div className="flex h-14 shrink-0 items-center justify-end pr-4 pl-2 tablet:h-11 tablet:p-0">
          <IconButton label="닫기" icon={<CloseIcon />} onClick={close} className="tablet:-mr-3" />
        </div>

        <main className="flex grow flex-col gap-5 px-5 pt-2 tablet:grow-0 tablet:gap-3.5 tablet:p-0">
          <div className="flex flex-col gap-2 tablet:gap-3.5">
            {/* 모바일은 두 줄, 태블릿부터 한 줄이다 */}
            <h1 className="text-setup-title leading-[1.4] font-bold text-fg tablet:leading-[1.35]">
              홈 화면에 추가하면 <br className="tablet:hidden" />
              다음 주에 알려드려요
            </h1>
            {/* 태블릿 · 데스크톱 시안은 둘째 문장만 있다 */}
            <p className="text-body leading-[1.55] text-fg-sub">
              <span className="tablet:hidden">
                주간 보고 알림은 우리동네체온계를 홈 화면에 추가한 뒤 받을 수 있어요.{' '}
              </span>
              알림을 켜지 않아도 홈에서 같은 내용을 볼 수 있어요.
            </p>
          </div>

          {guide ? (
            <GuideSection guideKey={guide} visibility="always" />
          ) : (
            <>
              {MOBILE_GUIDE_KEYS.map((key) => (
                <GuideSection key={key} guideKey={key} visibility="mobile" />
              ))}
              {WIDE_GUIDE_KEYS.map((key) => (
                <GuideSection key={key} guideKey={key} visibility="wide" />
              ))}
            </>
          )}
        </main>

        {/*
          모바일은 `알림 켜기` 위 · `나중에 할게요`(회색 글자) 아래, 태블릿 · 데스크톱은 한 줄에 `나중에 할게요`(회색 버튼) · `알림 켜기` 다.
          모양이 달라 `나중에 할게요` 를 두 번 그리고 폭으로 하나만 보인다(숨긴 쪽은 display: none 이라 한 번만 읽힌다).
          한 줄일 때는 두 버튼이 폭을 반씩 나눈다 — Button 은 기본이 shrink-0 이라 그대로 두면 각자 100% 로 넘친다
        */}
        <div className="sticky bottom-0 flex flex-col gap-2.5 bg-bg px-5 pt-3 pb-sheet tablet:static tablet:flex-row tablet:p-0">
          <div className="hidden tablet:flex tablet:flex-1">
            <Button variant="secondary" fullWidth onClick={close}>
              나중에 할게요
            </Button>
          </div>
          <Button
            fullWidth
            className="tablet:flex-1"
            onClick={() => show({ message: '알림 켜기는 준비하고 있어요' })}
          >
            알림 켜기
          </Button>
          <div className="flex flex-col tablet:hidden">
            <Button variant="subtle" onClick={close}>
              나중에 할게요
            </Button>
          </div>
        </div>
      </div>

      <ToastRegion
        toast={toast}
        onAction={dismiss}
        className="fixed inset-x-0 bottom-40 px-page-mobile tablet:bottom-8 tablet:mx-auto tablet:w-dialog-tablet tablet:px-0"
      />
    </div>
  )
}
