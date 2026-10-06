import clsx from 'clsx'

import { SearchIcon } from '@/components/icons'
import { REGION_STATUSES, STATUS_FILL_CLASS, STATUS_LABEL, STATUS_TEXT_CLASS } from '@/lib/status'

import type { MapDistrict } from './types'

/**
 * 지도 자리 (Map-* 시안의 지도 영역). **지도 그림만 임시다** — 행정동 경계(SGIS) · 지도 라이브러리가 붙기 전까지
 * 색칠한 행정동 대신 같은 동네를 목록으로 보여 고르게 한다. 위 찾기 · 기준 표시와 아래 범례는 시안 자리 그대로다.
 *
 * | 폭 | 구성 |
 * | --- | --- |
 * | 모바일 | 화면 폭 · 테두리 없음. 위에 찾기(48) 아래 기준 표시(32)를 세로로, 범례 없음(시안) |
 * | 태블릿 · 데스크톱 | 테두리 · 모서리 16. 찾기(44 · 최대 360) 옆에 기준 표시(44), 왼쪽 아래 범례 4단계 |
 *
 * 목록의 동네는 칠하는 색과 상태 글자를 함께 보인다. 자료 부족은 회색 · `자료 부족` 글자만이고 수치가 없다.
 * 모바일은 아래 동네 정보 시트가 지도 아래쪽을 20 덮어(시안) 그만큼 아래를 비운다.
 */
export function MapView({
  weekLabel,
  districts,
  mineCode,
  selectedCode,
  onSelect,
  onSearch,
}: {
  weekLabel: string
  districts: readonly MapDistrict[]
  /** `(내 동네)` 를 붙일 동네. 회원이 내 동네를 정하지 않았으면 null 이다(`MapScreen`) */
  mineCode: string | null
  selectedCode: string | null
  onSelect: (code: string) => void
  /** 행정동 이름으로 찾기. 둘러볼 동네 고르기(행정동 검색)를 연다(`MapScreen`, #204) */
  onSearch: () => void
}) {
  return (
    <div className="relative flex min-h-80 grow flex-col gap-3 bg-section p-3 pb-8 tablet:rounded-card tablet:border tablet:border-divider tablet:p-4">
      <div className="flex flex-col items-start gap-2 tablet:flex-row">
        <button
          type="button"
          onClick={onSearch}
          className="flex h-12 w-full cursor-pointer items-center gap-2 rounded-button border border-divider bg-bg px-3.5 text-body text-fg-sub tablet:h-11 tablet:max-w-90 tablet:grow"
        >
          <SearchIcon size={20} />
          <span>행정동 이름으로 찾기</span>
        </button>
        <span className="flex h-8 shrink-0 items-center rounded-chip border border-divider bg-bg px-3 text-caption font-semibold text-fg tablet:h-11 tablet:rounded-button tablet:px-3.5 tablet:text-sub">
          시민 자가보고 · {weekLabel}
        </span>
      </div>

      <div className="flex grow flex-col items-center justify-center gap-3 py-2 text-center">
        <p className="text-body font-semibold text-fg">행정동 지도는 준비하고 있어요</p>
        {districts.length === 0 ? (
          <p className="text-sub leading-normal text-fg-sub">
            이번 주 동네별 자료가 아직 없어요. 집계가 끝나면 보여 드려요.
          </p>
        ) : (
          <>
            <p className="text-sub text-fg-sub">지도 대신 목록에서 동네를 골라 보세요</p>
            <ul aria-label="동네 목록" className="flex w-full max-w-90 flex-col gap-2">
              {districts.map((district) => (
                <li key={district.code}>
                  <DistrictButton
                    district={district}
                    mine={district.code === mineCode}
                    selected={district.code === selectedCode}
                    onSelect={onSelect}
                  />
                </li>
              ))}
            </ul>
          </>
        )}
      </div>

      <MapLegend className="hidden self-start tablet:flex" />
    </div>
  )
}

function DistrictButton({
  district,
  mine,
  selected,
  onSelect,
}: {
  district: MapDistrict
  mine: boolean
  selected: boolean
  onSelect: (code: string) => void
}) {
  const { status, regionName } = district.week
  return (
    <button
      type="button"
      aria-pressed={selected}
      onClick={() => onSelect(district.code)}
      className={clsx(
        'flex min-h-touch w-full cursor-pointer items-center gap-2.5 rounded-button bg-bg px-3.5 text-left',
        selected ? 'border-selected border-brand' : 'border-hairline border-divider',
      )}
    >
      <span
        aria-hidden="true"
        className={clsx('size-3 shrink-0 rounded-bar', STATUS_FILL_CLASS[status])}
      />
      <span className="grow text-body font-semibold text-fg">
        {regionName}
        {mine && <span className="font-medium text-fg-sub"> (내 동네)</span>}
      </span>
      <span className={clsx('shrink-0 text-sub font-semibold', STATUS_TEXT_CLASS[status])}>
        {STATUS_LABEL[status]}
      </span>
    </button>
  )
}

/** 범례 4단계 (Map-*-T · -D). 칠하는 색과 글자를 함께 둔다 — 자료 부족은 회색이다 */
export function MapLegend({ className }: { className?: string }) {
  return (
    <ul
      aria-label="범례"
      className={clsx(
        'gap-3 rounded-small border border-divider bg-bg px-3 py-2 text-caption text-fg',
        className,
      )}
    >
      {REGION_STATUSES.map((status) => (
        <li key={status} className="flex items-center gap-1.25">
          <span
            aria-hidden="true"
            className={clsx('size-2.5 rounded-bar', STATUS_FILL_CLASS[status])}
          />
          {STATUS_LABEL[status]}
        </li>
      ))}
    </ul>
  )
}
