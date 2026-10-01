'use client'

import { type ReactNode, useId } from 'react'

import clsx from 'clsx'

import { CheckIcon, SearchIcon } from '@/components/icons'
import type { District } from '@/features/region/types'
import type { DistrictSearch } from '@/features/region/use-district-search'
import { withGwaWa } from '@/lib/korean'

/*
 * 동네 고르기 조각. 동네 선택(Setup-1 · Setup-1-empty · Setup-1-browse)과 폐지된 동네 다시 고르기(Setup-1-reselect)가 같이 쓴다.
 * **위치 권한을 요청하지 않는다** — 행정동은 사용자가 직접 고른다.
 */

/**
 * 행정동 검색 칸. 이름은 "행정동 이름" 이다(라벨은 화면에서 숨긴다).
 * 입력칸 규칙 (docs/design/auth/README.md): 높이 52 · 모서리 12 · 기본 1px 회색 · 포커스 2px 네이비.
 * 테두리가 굵어져도 글자가 밀리지 않게 안쪽 여백을 1px 씩 줄여 맞춘다
 */
export function DistrictSearchInput({
  value,
  onChange,
  readOnly,
}: {
  value: string
  onChange: (value: string) => void
  /** 보내는 중 — 고친 검색어로 선택이 지워지지 않게 한다 */
  readOnly?: boolean | undefined
}) {
  const inputId = useId()
  return (
    <div className="relative">
      <SearchIcon className="pointer-events-none absolute top-4 left-3.5 text-fg-sub" />
      <label htmlFor={inputId} className="sr-only">
        행정동 이름
      </label>
      <input
        id={inputId}
        type="search"
        value={value}
        onChange={(event) => onChange(event.target.value)}
        readOnly={readOnly}
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
  )
}

export type DistrictOption = {
  district: District
  /** 이름 아래 13px 설명. 기본은 시군구다 */
  detail: string
}

/** 시군구를 설명으로 단 선택지 */
export function districtOptions(districts: readonly District[]): DistrictOption[] {
  return districts.map((district) => ({ district, detail: district.sigungu }))
}

/**
 * 행정동 선택지 목록. 한 줄에 하나(높이 60 이상, 아래 구분선), 고르면 굵게 · 체크 표시.
 * 하나만 고르므로 라디오로 둔다(시안은 `aria-pressed` 버튼).
 */
export function DistrictOptionList({
  legend,
  options,
  selectedCode,
  onSelect,
}: {
  /** 목록 이름(화면에서 숨긴다). 예: "검색 결과" */
  legend: string
  options: readonly DistrictOption[]
  selectedCode: string | null
  onSelect: (district: District) => void
}) {
  if (options.length === 0) return null
  return (
    <fieldset>
      <legend className="sr-only">{legend}</legend>
      {options.map(({ district, detail }) => {
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
              <span className="text-sub text-fg-sub">{detail}</span>
            </span>
            {selected && <CheckIcon className="shrink-0 text-brand" />}
          </label>
        )
      })}
    </fieldset>
  )
}

/** 검색 결과로 그릴 행정동. 불러오는 동안은 앞 검색어의 결과를 그대로 보인다 */
export function searchResultsOf(search: DistrictSearch): District[] {
  return search.status === 'done' || search.status === 'loading' ? search.results : []
}

/**
 * 검색 결과 알림의 내용. 늘 그려 둔 `role="status"` 영역 안에 둔다 — 스크린리더는 이미 있던 영역의 내용이 바뀔 때 읽는다.
 * 찾음(화면에서 숨긴 개수) · 결과 없음(Setup-1-empty) · 불러오지 못함.
 */
export function SearchNotices({ search, keyword }: { search: DistrictSearch; keyword: string }) {
  const results = searchResultsOf(search)
  return (
    <>
      {search.status === 'done' && results.length > 0 && (
        <p className="sr-only">동네 {results.length}곳을 찾았어요</p>
      )}
      {search.status === 'done' && results.length === 0 && (
        <PickerNotice
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
        <PickerNotice title="동네를 불러오지 못했어요" description="잠시 뒤 다시 검색해 주세요." />
      )}
    </>
  )
}

/** 결과 없음 (Setup-1-empty) · 불러오지 못함. 불러오지 못함은 시안이 없어 결과 없음 모양을 같이 쓴다 */
export function PickerNotice({ title, description }: { title: string; description: ReactNode }) {
  return (
    <div className="flex flex-col items-center gap-2 px-3 py-8 text-center">
      <SearchIcon size={40} strokeWidth={1.6} className="text-muted-bar" />
      <p className="text-section-title font-semibold text-fg">{title}</p>
      <p className="text-body-strong leading-[1.55] text-fg-sub">{description}</p>
    </div>
  )
}
