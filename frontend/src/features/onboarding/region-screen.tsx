'use client'

import { type ReactNode, useId, useState } from 'react'
import { useRouter } from 'next/navigation'

import clsx from 'clsx'

import { Button } from '@/components/button'
import { CheckIcon, SearchIcon } from '@/components/icons'
import type { District } from '@/features/region/types'
import { type DistrictSearch, useDistrictSearch } from '@/features/region/use-district-search'
import { withGwaWa } from '@/lib/korean'

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
}

/**
 * S02-1 동네 선택. 행정동을 검색해 하나 고른다. **위치 권한을 요청하지 않는다** — GPS 로 정하지 않는다.
 *
 * | 모드 | 주소 | 단계 | 버튼 | 다음 |
 * | --- | --- | --- | --- | --- |
 * | 기본 | `/setup/region` | 1 / 4 | 다음 | 성인 확인 `/setup/adult` |
 * | 둘러보기 | `/browse/region` | 없음 | 이 동네 보기 | 고른 동네 홈 `/?region=<code>` |
 *
 * 고른 동네는 OnboardingProvider 가 갖는다. 성인 확인에서 돌아오면 고른 동네 이름으로 다시 검색해 선택을 보인다.
 * 검색어를 고치면 선택을 지운다 — 고른 동네가 목록에서 사라졌는데 버튼만 켜져 있지 않게 한다.
 *
 * 시안(정본): docs/design/auth/screens/ 의 Setup-1 · Setup-1-empty · Setup-1-browse (+ -T · -D)
 */
export function RegionScreen({ browse = false }: RegionScreenProps) {
  const router = useRouter()
  const { district, setDistrict, goBack } = useOnboarding()
  const [query, setQuery] = useState(district?.name ?? '')
  const search = useDistrictSearch(query)
  const inputId = useId()
  const mode = browse ? 'browse' : 'setup'

  function next() {
    if (!district) return
    router.push(browse ? browseHomePath(district.code) : SETUP_ADULT_PATH)
  }

  return (
    <OnboardingLayout
      step={browse ? undefined : 1}
      onBack={() => goBack(browse ? START_PATH : SETUP_REGION_PREVIOUS)}
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

        {/*
          입력칸 규칙 (docs/design/auth/README.md): 높이 52 · 모서리 12 · 기본 1px 회색 · 포커스 2px 네이비.
          테두리가 굵어져도 글자가 밀리지 않게 안쪽 여백을 1px 씩 줄여 맞춘다
        */}
        <div className="relative">
          <SearchIcon className="pointer-events-none absolute top-4 left-3.5 text-fg-sub" />
          <label htmlFor={inputId} className="sr-only">
            행정동 이름
          </label>
          <input
            id={inputId}
            type="search"
            value={query}
            onChange={(event) => {
              setQuery(event.target.value)
              // 미리 채운 검색어(고른 동네 이름)는 onChange 를 거치지 않아 선택이 남는다
              setDistrict(null)
            }}
            placeholder="행정동 이름"
            autoComplete="off"
            enterKeyHint="search"
            className={clsx(
              'h-13 w-full rounded-button bg-bg text-section-title text-fg outline-none placeholder:text-fg-muted',
              'border-hairline border-inactive-bar pr-4.25 pl-11.25',
              'focus:border-selected focus:border-brand focus:pr-4 focus:pl-11',
            )}
          />
        </div>

        <SearchResults
          search={search}
          keyword={query.trim()}
          selectedCode={district?.code ?? null}
          onSelect={setDistrict}
        />
      </div>
    </OnboardingLayout>
  )
}

function SearchResults({
  search,
  keyword,
  selectedCode,
  onSelect,
}: {
  search: DistrictSearch
  /** 결과 없음 문구에 보이는 지금 검색어 */
  keyword: string
  selectedCode: string | null
  onSelect: (district: District) => void
}) {
  const results = search.status === 'done' || search.status === 'loading' ? search.results : []

  return (
    <>
      {results.length > 0 && (
        <fieldset>
          <legend className="sr-only">검색 결과</legend>
          {results.map((district) => {
            const selected = district.code === selectedCode
            return (
              <label
                key={district.code}
                className="flex min-h-15 cursor-pointer items-center justify-between gap-3 border-b border-divider has-focus-visible:outline-2 has-focus-visible:-outline-offset-2 has-focus-visible:outline-brand"
              >
                <input
                  type="radio"
                  name="district"
                  value={district.code}
                  checked={selected}
                  onChange={() => onSelect(district)}
                  className="sr-only"
                />
                <span className="flex flex-col gap-0.5">
                  <span
                    className={clsx(
                      'text-section-title text-fg',
                      selected ? 'font-bold' : 'font-medium',
                    )}
                  >
                    {district.name}
                  </span>
                  <span className="text-sub text-fg-sub">{district.sigungu}</span>
                </span>
                {selected && <CheckIcon className="shrink-0 text-brand" />}
              </label>
            )
          })}
        </fieldset>
      )}

      {/*
        검색 결과 알림. 스크린리더는 이미 있던 영역의 내용이 바뀔 때 읽으므로 늘 그려 둔다.
        비어 있어도 간격 한 칸을 차지해서 목록 아래(맨 끝)에 둔다
      */}
      <div role="status">
        {search.status === 'done' && results.length > 0 && (
          <p className="sr-only">동네 {results.length}곳을 찾았어요</p>
        )}
        {search.status === 'done' && results.length === 0 && (
          <Notice
            title={`‘${keyword}’${withGwaWa(keyword)} 맞는 행정동이 없어요`}
            description={
              <>
                동 이름을 다시 확인해 주세요.
                <br />
                예: 망원1동, 역삼2동
              </>
            }
          />
        )}
        {search.status === 'error' && (
          <Notice title="동네를 불러오지 못했어요" description="잠시 뒤 다시 검색해 주세요." />
        )}
      </div>
    </>
  )
}

/** 결과 없음 (Setup-1-empty) · 불러오지 못함. 불러오지 못함은 시안이 없어 결과 없음 모양을 같이 쓴다 */
function Notice({ title, description }: { title: string; description: ReactNode }) {
  return (
    <div className="flex flex-col items-center gap-2 px-3 py-8 text-center">
      <SearchIcon size={40} strokeWidth={1.6} className="text-muted-bar" />
      <p className="text-section-title font-semibold text-fg">{title}</p>
      <p className="text-body-strong leading-[1.55] text-fg-sub">{description}</p>
    </div>
  )
}
