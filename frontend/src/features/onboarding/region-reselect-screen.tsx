'use client'

import { useEffect, useState } from 'react'
import { useSearchParams } from 'next/navigation'

import { AlertBox } from '@/components/alert-box'
import { Button } from '@/components/button'
import { type MemberRegion, saveRegion } from '@/features/auth/auth-client'
import {
  carriedParams,
  NEXT_PARAM,
  safeNextPath,
  stepTarget,
  targetAfter,
} from '@/features/auth/required-steps'
import { useMemberRequirements } from '@/features/auth/use-member-requirements'
import { useMockAuth } from '@/features/auth/use-mock-auth'
import { listSuccessorDistricts } from '@/features/region/region-client'
import type { District, ReselectCandidate } from '@/features/region/types'
import { useDistrictSearch } from '@/features/region/use-district-search'
import { withIGa } from '@/lib/korean'
import { useActiveRef } from '@/lib/use-active-ref'
import { useHydrated } from '@/lib/use-hydrated'

import {
  type DistrictOption,
  DistrictOptionList,
  districtOptions,
  DistrictSearchInput,
  PickerNotice,
  SearchNotices,
  searchResultsOf,
} from './district-picker'
import { useOnboarding } from './onboarding-context'
import { OnboardingLayout } from './onboarding-layout'

type CandidateLoad =
  { status: 'loading' } | { status: 'done'; candidates: ReselectCandidate[] } | { status: 'error' }

type Settled =
  | { code: string; status: 'done'; candidates: ReselectCandidate[] }
  | { code: string; status: 'error' }

/** 옛 동네의 후보(이어 받은 동네). 코드가 바뀌면 앞 응답은 버린다 */
function useCandidates(code: string | null): CandidateLoad {
  const [settled, setSettled] = useState<Settled | null>(null)
  useEffect(() => {
    if (!code) return
    let stale = false
    listSuccessorDistricts(code).then(
      (candidates) => {
        if (!stale) setSettled({ code, status: 'done', candidates })
      },
      () => {
        if (!stale) setSettled({ code, status: 'error' })
      },
    )
    return () => {
      stale = true
    }
  }, [code])
  return settled !== null && settled.code === code ? settled : { status: 'loading' }
}

/**
 * S02-1 폐지된 동네 다시 고르기 (`/setup/region?reselect=1`, Setup-1-reselect). 회원이 고른 행정동이 행정구역 개편으로 없어졌을 때
 * 홈 · 내 정보가 보낸다(`features/me/member-gate.ts`). **위치 권한을 요청하지 않는다** — 행정동은 직접 고른다.
 *
 * - 옛 동네 이름을 넣은 안내 → 검색 칸 → 목록 → `이 동네로 바꾸기`. 단계 표시는 없다
 * - 검색어가 비었으면 이어 받은 동네 후보(`listSuccessorDistricts`, 옛 동네 일부면 `옛 ○○1동 일부`)를, 검색어가 있으면 검색 결과를
 *   보인다. 검색어를 고치면 선택을 지운다(동네 선택과 같다)
 * - **첫 진입 Provider 의 가입 초안 · 고른 동네를 쓰지 않는다.** 회원의 동네를 바로 저장한다(`saveRegion`). 성공하면 목 프로필이
 *   먼저 바뀌고(화면과 무관), 화면이 떠 있으면 남은 조건 화면이나 `?next=`(허용 목록 밖이면 홈)로 기록을 바꿔 간다
 * - 보내는 중에는 버튼이 꺼지고(`aria-disabled`) 검색 칸은 읽기 전용이다. 실패하면 빨강 상자로 알리고 다시 누를 수 있다
 * - **뒤로 버튼이 없다**(시안에도 없음). 동네가 없으면 보고를 셀 수 없어 고르기 전에는 나가지 않는다
 * - 하이드레이션을 마친 뒤 판단한다: 비회원이거나 동네 조건이 없으면 남은 조건 화면(약관 재동의가 먼저)이나 `?next=` 로 보낸다
 *
 * 시안(정본): docs/design/auth/screens/ 의 Setup-1-reselect (+ -T · -D)
 */
export function RegionReselectScreen() {
  const searchParams = useSearchParams()
  const hydrated = useHydrated()
  const auth = useMockAuth()
  const requirements = useMemberRequirements()
  const { replace } = useOnboarding()
  const active = useActiveRef()

  const [query, setQuery] = useState('')
  const search = useDistrictSearch(query)
  const [selected, setSelected] = useState<District | null>(null)
  const [pending, setPending] = useState(false)
  const [failed, setFailed] = useState(false)
  // 보내는 동안의 옛 동네. 저장에 성공하면 목 프로필의 폐지 표시가 먼저 꺼져 조건에서 빠진다 — 이동할 때까지 같은 화면을 그린다
  const [submittingFrom, setSubmittingFrom] = useState<MemberRegion | null>(null)

  // 약관 재동의가 남았으면 그쪽이 먼저다. 이 화면은 첫 조건이 동네일 때만 그린다
  const showing = hydrated && requirements.steps[0] === 'region'
  const oldRegion = showing ? requirements.abolishedRegion : pending ? submittingFrom : null
  const redirect =
    hydrated && !showing
      ? stepTarget(
          requirements.steps,
          safeNextPath(searchParams.get(NEXT_PARAM)),
          carriedParams(searchParams),
        )
      : null
  useEffect(() => {
    // 저장에 성공한 뒤의 이동은 save 가 한다(조건이 먼저 사라져도 여기서 한 번 더 보내지 않는다)
    if (redirect && !pending) replace(redirect)
  }, [redirect, pending, replace])

  const candidates = useCandidates(oldRegion?.code ?? null)

  if (!oldRegion) return null

  const keyword = query.trim()
  const options: DistrictOption[] = keyword
    ? districtOptions(searchResultsOf(search))
    : candidates.status === 'done'
      ? candidates.candidates.map((candidate) => ({
          district: candidate,
          detail: candidate.partOfAbolished
            ? `${candidate.sigungu} · 옛 ${oldRegion.name} 일부`
            : candidate.sigungu,
        }))
      : []
  const blocked = !selected || pending

  async function save() {
    if (!selected || pending || !oldRegion) return
    setPending(true)
    setFailed(false)
    setSubmittingFrom(oldRegion)
    try {
      await saveRegion(selected)
    } catch {
      if (active.current) {
        setFailed(true)
        setPending(false)
      }
      return
    }
    // 목 프로필(연동 때는 서버)에는 이미 바뀐 동네가 남았다. 이동만 화면이 떠 있을 때 한다
    if (!active.current) return
    replace(targetAfter('region', auth, searchParams))
  }

  return (
    <OnboardingLayout
      panelTitle={
        <>
          행정구역이 바뀌면
          <br />
          다시 여쭤볼게요
        </>
      }
      footer={
        <Button fullWidth aria-disabled={blocked || undefined} onClick={() => void save()}>
          이 동네로 바꾸기
        </Button>
      }
    >
      <div className="flex flex-col gap-5">
        <h1 className="text-setup-title leading-[1.4] font-bold text-fg">
          동네를 다시 골라 주세요
        </h1>

        <AlertBox tone="info">
          고르셨던 {oldRegion.name}
          {withIGa(oldRegion.name)} 행정구역 개편으로 바뀌었어요. 지금 사는 행정동을 다시 골라
          주세요.
        </AlertBox>

        <DistrictSearchInput
          value={query}
          readOnly={pending}
          onChange={(value) => {
            setQuery(value)
            setSelected(null)
          }}
        />
        <DistrictOptionList
          legend={keyword ? '검색 결과' : '다시 고를 동네 후보'}
          options={options}
          selectedCode={selected?.code ?? null}
          onSelect={(district) => {
            if (!pending) setSelected(district)
          }}
        />
        {/* 후보 · 검색 결과 알림. 늘 그려 두고 내용만 바꾼다 */}
        <div role="status">
          {keyword ? (
            <SearchNotices search={search} keyword={keyword} />
          ) : (
            <CandidateNotices load={candidates} />
          )}
        </div>

        {failed && (
          <AlertBox tone="danger">동네를 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.</AlertBox>
        )}
      </div>
    </OnboardingLayout>
  )
}

/** 후보 알림. 불러오는 중 · 불러오지 못함 · 후보 없음(검색으로 고르게 한다)은 시안에 없어 더했다 */
function CandidateNotices({ load }: { load: CandidateLoad }) {
  if (load.status === 'loading') {
    return <p className="text-body-strong text-fg-sub">후보 동네를 불러오고 있어요</p>
  }
  if (load.status === 'error') {
    return (
      <PickerNotice
        title="후보 동네를 불러오지 못했어요"
        description="동 이름으로 검색해 주세요."
      />
    )
  }
  // 후보가 없으면(서버가 모르는 옛 동네) 불러오지 못함과 같은 자리 · 모양으로 검색하게 한다
  return load.candidates.length > 0 ? (
    <p className="sr-only">다시 고를 동네 후보 {load.candidates.length}곳이 있어요</p>
  ) : (
    <PickerNotice title="후보 동네가 없어요" description="동 이름으로 검색해 주세요." />
  )
}
