'use client'

import { useEffect, useState } from 'react'
import { useRouter } from 'next/navigation'

import { Button } from '@/components/button'
import { useDistrictSearch } from '@/features/region/use-district-search'
import { useNavTrail } from '@/lib/use-nav-trail'

import { type BrowseReturn, browseReturnHref } from './browse-return'
import {
  DistrictOptionList,
  districtOptions,
  DistrictSearchInput,
  SearchNotices,
  searchResultsOf,
} from './district-picker'
import { useOnboarding } from './onboarding-context'
import { OnboardingLayout } from './onboarding-layout'
import {
  browseHomePath,
  LOGIN_PATH,
  SETUP_ADULT_PATH,
  SIGNUP_ACCOUNT_PATH,
  START_PATH,
} from './paths'

/**
 * 가입 흐름 동네 선택의 앞 단계. 카카오 신규 회원은 로그인에서, 이메일 가입은 비밀번호 · 닉네임(S13-4)에서 온다.
 * 주소로 바로 들어오면 첫 후보(로그인)로 바꿔 간다. 둘러보기의 앞 단계는 시작 화면이다
 */
const SETUP_REGION_PREVIOUS = [LOGIN_PATH, SIGNUP_ACCOUNT_PATH] as const

/** 기본(보고하러 가는 길) · 둘러보기 문구 (Setup-1 · Setup-1-browse) */
const COPY = {
  setup: {
    title: '어느 동네에 사시나요?',
    description: '행정동을 직접 골라 주세요. 위치 정보는 사용하지 않아요.',
    panelTitle: (
      <>
        행정동만 골라요.
        <br />
        위치 정보는 쓰지 않아요
      </>
    ),
  },
  browse: {
    title: '어느 동네를 볼까요?',
    description: '둘러볼 행정동을 골라 주세요. 위치 정보는 사용하지 않아요.',
    panelTitle: (
      <>
        로그인 없이도
        <br />
        동네 현황을 볼 수 있어요
      </>
    ),
  },
} as const

export type RegionScreenProps = {
  /** 보고 없이 둘러보기(`/browse/region`). 단계 표시 없이 고른 동네의 홈으로 바로 간다 */
  browse?: boolean
  /**
   * 둘러보기의 돌아갈 곳(`?next=`, 머리줄 동네 이름에서 옴 — `browse-return.ts`). 있으면 고른 뒤 그 화면에 새 동네를 붙여 기록을 바꿔 가고,
   * 뒤로는 앞 화면을 따지지 않고 기록을 되돌린다(앞 기록이 없으면 지금 둘러보던 동네의 그 화면). 둘러보기에서만 쓴다
   */
  browseReturn?: BrowseReturn | null
  /**
   * 카카오 로그인에서 돌아왔다(`/setup/region?from=kakao`). 가입 종류가 아직 카카오가 아니면 가입 초안을 비우고 카카오로 둔다 —
   * 홈의 로그인 안내 시트처럼 첫 진입 Provider 밖에서 카카오로 시작하면 가입 종류가 없어 S02-3 이 `/login` 으로 돌려보낸다.
   * 둘러보기에서는 쓰지 않는다
   */
  fromKakao?: boolean
}

/**
 * S02-1 동네 선택. 행정동을 검색해 하나 고른다. **위치 권한을 요청하지 않는다** — GPS 로 정하지 않는다.
 *
 * | 모드 | 주소 | 단계 | 버튼 | 다음 |
 * | --- | --- | --- | --- | --- |
 * | 기본 | `/setup/region` | 1 / 4 | 다음 | 성인 확인 `/setup/adult` |
 * | 둘러보기 | `/browse/region` | 없음 | 이 동네 보기 | 고른 동네 홈 `/?region=<code>` |
 * | 둘러보기(머리줄에서) | `/browse/region?next=<경로>` | 없음 | 이 동네 보기 | 그 화면 `<경로>?region=<code>` (기록을 바꿔 감) |
 *
 * 폐지된 동네 다시 고르기(`/setup/region?reselect=1`)는 가입 초안을 쓰지 않고 회원 동네를 바로 저장해 `RegionReselectScreen` 이 맡는다.
 * 검색 칸 · 목록 · 결과 알림은 `district-picker.tsx` 를 같이 쓴다.
 *
 * 고른 동네는 OnboardingProvider 가 갖는다. 성인 확인에서 돌아오면 고른 동네 이름으로 다시 검색해 선택을 보인다.
 * 검색어를 고치면 선택을 지운다 — 고른 동네가 목록에서 사라졌는데 버튼만 켜져 있지 않게 한다.
 *
 * 카카오에서 돌아오면(`fromKakao`) 가입 종류를 카카오로 한 번만 둔다. 이미 카카오면(S13-1 에서 카카오로 시작 · 뒤로 가기로
 * 다시 옴) 그대로 두어 진행 중인 카카오 가입의 마무리 진행(`membership`)을 지우지 않는다. 다른 가입 종류였다면 새 가입 시도라
 * `resetSignup` 이 초안과 진행을 함께 비운다(로그인 화면에서 카카오로 시작할 때와 같다).
 *
 * 시안(정본): docs/design/auth/screens/ 의 Setup-1 · Setup-1-empty · Setup-1-browse (+ -T · -D)
 */
export function RegionScreen({
  browse = false,
  browseReturn = null,
  fromKakao = false,
}: RegionScreenProps) {
  const router = useRouter()
  const { district, setDistrict, goBack, replace, signup, resetSignup, updateSignup } =
    useOnboarding()
  const { goBack: goBackInTrail } = useNavTrail()
  const returning = browse ? browseReturn : null

  const needsKakaoMethod = !browse && fromKakao && signup.method !== 'kakao'
  useEffect(() => {
    if (!needsKakaoMethod) return
    resetSignup()
    updateSignup({ method: 'kakao' })
  }, [needsKakaoMethod, resetSignup, updateSignup])
  const [query, setQuery] = useState(district?.name ?? '')
  const search = useDistrictSearch(query)
  const mode = browse ? 'browse' : 'setup'

  function next() {
    if (!district) return
    // 머리줄에서 왔으면 돌아갈 화면으로 기록을 바꿔 간다 — 뒤로 가기로 고르기 화면에 다시 오지 않고, 그 앞은 고르기 전 화면이다.
    // 시작 화면의 둘러보기는 지금처럼 홈에 쌓아 간다(홈에서 뒤로 가면 다시 골라 볼 수 있다)
    if (returning) replace(browseReturnHref(returning, district.code))
    else router.push(browse ? browseHomePath(district.code) : SETUP_ADULT_PATH)
  }

  function back() {
    // 머리줄은 여러 화면에 있어 앞 화면이 정해져 있지 않다 — 앱 안에서 왔으면 어디서든 되돌린다(돌아갈 곳이 있는 로그인과 같다)
    if (returning) goBackInTrail(browseReturnHref(returning, returning.region))
    else goBack(browse ? START_PATH : SETUP_REGION_PREVIOUS)
  }

  return (
    <OnboardingLayout
      step={browse ? undefined : 1}
      onBack={back}
      panelTitle={COPY[mode].panelTitle}
      footer={
        <Button fullWidth disabled={!district} onClick={next}>
          {browse ? '이 동네 보기' : '다음'}
        </Button>
      }
    >
      <div className="flex flex-col gap-5">
        <div>
          <h1 className="text-setup-title leading-[1.4] font-bold text-fg">{COPY[mode].title}</h1>
          <p className="mt-2 text-body leading-[1.6] text-fg-sub">{COPY[mode].description}</p>
        </div>

        <DistrictSearchInput
          value={query}
          onChange={(value) => {
            setQuery(value)
            // 미리 채운 검색어(고른 동네 이름)는 onChange 를 거치지 않아 선택이 남는다
            setDistrict(null)
          }}
        />
        <DistrictOptionList
          legend="검색 결과"
          options={districtOptions(searchResultsOf(search))}
          selectedCode={district?.code ?? null}
          onSelect={setDistrict}
        />
        {/*
          검색 결과 알림. 스크린리더는 이미 있던 영역의 내용이 바뀔 때 읽으므로 늘 그려 둔다.
          비어 있어도 간격 한 칸을 차지해서 목록 아래(맨 끝)에 둔다
        */}
        <div role="status">
          <SearchNotices search={search} keyword={query.trim()} />
        </div>
      </div>
    </OnboardingLayout>
  )
}
